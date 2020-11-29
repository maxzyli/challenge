package com.aliyun.filter;

import com.aliyun.common.Packet;

import static com.aliyun.common.Const.*;

/**
 * Page容器，主要负责管理Page
 */
public class Container {
    private static final int len = 16;


    private static final Page[] emptyPages = new Page[len];//空的页
    private static final Page[] fullPages = new Page[len];//读满了数据的页
    private static final Page[] handlePages = new Page[len];//处理完索引和错误的页
    public static final Packet errPkts[] = new Packet[1024];//用于存放错误
    public static int errPktIndex = 0;
    public static int total_err_pkt = 10000;
    public static final int per_page[] = new int[1000];//表示每100000行在第几页


    //用于存放错误的日志

    static {
        for (int i = 0; i < emptyPages.length; i++) {
            emptyPages[i] = new Page();
        }
        System.out.println("init memory finish");
    }


    public static void putErrPkt(Packet packet, int pageIndex) {
        synchronized (errPkts) {
            errPkts[errPktIndex] = packet;
            per_page[errPktIndex] = pageIndex;
            errPktIndex++;
            errPkts.notify();
        }
    }

    public static Packet getErrPkt(int n) {
        synchronized (errPkts) {
            if (errPkts[n] == null) {
                try {
                    errPkts.wait();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return errPkts[n];
    }


    //从empty中取
    public static Page getEmptyPage(int i) {
//        long l = System.currentTimeMillis();
        synchronized (emptyPages) {
            Page page = emptyPages[i % len];
            while (page == null) {
                try {
                    emptyPages.wait();
                    page = emptyPages[i % len];
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            page.pageIndex = i;
//            System.out.println("get empty page,page=" + i + ", time=" + (System.currentTimeMillis() - l));
            return page;
        }
    }


    //放入empty
    public static void moveHandleToEmpty(int i) {
        synchronized (emptyPages) {
            if (i < 0) return;
            try {
                emptyPages[i % len] = handlePages[i % len];
                handlePages[i % len] = null;
                emptyPages.notify();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    //从full中取
    public static Page getFullPage(int i) {
//        long l = System.currentTimeMillis();
        synchronized (fullPages) {
            Page page = fullPages[i % len];
            while (page == null) {
                try {
                    fullPages.wait();
                    page = fullPages[i % len];
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
//            System.out.println("get full page,page=" + i + ", time=" + (System.currentTimeMillis() - l));
            return page;
        }
    }

    //放入full
    public static void moveEmptyToFull(int i) {
        synchronized (fullPages) {
            try {
                fullPages[i % len] = emptyPages[i % len];
                emptyPages[i % len] = null;
                fullPages.notify();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    //从handle中取
    public static Page getHandlePage(int i) {
//        long l = System.currentTimeMillis();
        synchronized (handlePages) {
            if (i >= total_page_count || i < 0) return null;
            Page page = handlePages[i % len];
            while (page == null) {
                try {
                    handlePages.wait();
                    page = handlePages[i % len];
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
//            System.out.println("get handle page,page=" + i + ", time=" + (System.currentTimeMillis() - l));
            return page;
        }
    }

    //放入handle
    public static void moveFullToHandle(int i) {
        synchronized (handlePages) {
            try {
                handlePages[i % len] = fullPages[i % len];
                fullPages[i % len] = null;
                handlePages.notify();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 创建索引->找出错误->发送错误
     */
    public static void handleData() {
        for (int i = 0; i < total_page_count; i++) {
            Page page = Container.getFullPage(i);
            page.createIndexAndFindError();
            moveFullToHandle(i);//创建完索引，就可以将这个移动到其他地方了
            if (i == total_page_count - 1) {//表示是最后一页了
                filter.sendPacket(page.errPkt);
                putErrPkt(page.errPkt, i);//将最后一页放入进去
            }
        }
        total_err_pkt = errPktIndex;
        System.out.println("-------------create index and find error Thread end------------------");
//        System.out.println("error count : " + Page.testCountErrorSet.size());
    }


//    static Packet errorPacket = new Packet(16, who, Packet.TYPE_MULTI_TRACE_ID);

    public static void handleErrorPacket() {

        int removeP = 0;
        for (int i = 0; i < total_err_pkt; i++) {
            Packet errPkt = getErrPkt(i);
            int start = i == 0 ? 0 : (per_page[i - 1] == 0 ? 0 : per_page[i - 1] - 1);
            int end = per_page[i] + 2;
            handleErrorPacket(start, end, errPkt);

            for (; removeP < start; removeP++) {
                moveHandleToEmpty(removeP);
            }
        }

        Packet endPacket = new Packet(1, who, Packet.TYPE_END);
        filter.sendPacket(endPacket);
        System.out.println("-------------query logs Thread end------------------");

    }

    public static void handleErrorPacket(int start, int end, Packet packet) {
        //处理本地错误traceId TODO 第一次会比较耗时，要等待下一个准备好才能查询
        long startTime = System.currentTimeMillis();
//        System.out.println("select by local trace id ,from [" + start + "," + end + ")");
        handelOnePacket(start, end, packet);
//        System.out.println("query error 1,from[" + start + "," + end + ")" + ",time=" + (System.currentTimeMillis() - startTime));
        //处理其他节点发送过来的traceId
        packet = filter.getRemoteErrorPacket();
//        System.out.println("select by remote trace id ,from [" + start + "," + end + ")");
        handelOnePacket(start, end, packet);
//        System.out.println("query error 2,from[" + start + "," + end + ")" + ",time=" + (System.currentTimeMillis() - startTime));

    }

    public static void handelOnePacket(int start, int end, Packet packet) {
        //packet大概27个
        byte[] bs = packet.getBs();
        int len = packet.getLen();
        for (int i = Packet.P_DATA; i < len; i += 16) {
            byte traceId[] = new byte[16];
            System.arraycopy(bs, i, traceId, 0, 16);
            Packet logsPacket = selectByTraceId(start, end, traceId);
            filter.sendPacket(logsPacket);
        }
    }

    static Packet packet = new Packet(64, who, Packet.TYPE_MULTI_LOG);

    static Log[] logs = new Log[500];
    static int logsLen = 0;

    public static Packet selectByTraceId(int start, int end, byte traceId[]) {
        packet.reset(who, Packet.TYPE_MULTI_LOG);
        packet.writePage(0);
        //先写入traceId
        packet.write(traceId, 0, traceId.length);
        logsLen = 0;
        for (int i = start; i < end; i++) {
            Page page = getHandlePage(i);
            if (page == null) break;
            int len = page.selectByTraceId(traceId, logs, logsLen);
            logsLen += len;
        }

        for (int i = 1; i < logsLen; i++) {
            for (int j = i; j > 0; j--) {
                if (compare(logs[j], logs[j - 1]) < 0) {
                    Log tmp = logs[j - 1];
                    logs[j - 1] = logs[j];
                    logs[j] = tmp;
                }
            }
        }

        //将traceId封装为Packet
        for (int i = 0; i < logsLen; i++) {
            Log l = logs[i];
            packet.writeWithDataLen(l.d, l.s, l.l);
            logs[i] = null;
        }
        return packet;
    }

    public static int compare(Log l1, Log l2) {
        //因为b1和b2的traceId都是一样的，可以随便指定一个开始位置
        for (int i = 20; i < 35; i++) {//20 时间的前面几位数可以不比较,35 j在超过时间之前就表完了
            if (l1.d[l1.s + i] == l2.d[l2.s + i]) continue;
            return l1.d[l1.s + i] - l2.d[l2.s + i];
        }
        return 0;
    }


    private static void printPage() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append((emptyPages[i] != null ? "1" : "_") + "\t");
        }
        sb.append("\n");
        for (int i = 0; i < len; i++) {
            sb.append((fullPages[i] != null ? "1" : "_") + "\t");
        }
        sb.append("\n");
        for (int i = 0; i < len; i++) {
            sb.append((handlePages[i] != null ? "1" : "_") + "\t");
        }
        sb.append("\n");
        System.out.println(sb.toString());
    }
}
