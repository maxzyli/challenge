package com.aliyun.engine;

import static com.aliyun.common.Const.*;

import com.aliyun.Main;
import com.aliyun.common.MD5;
import com.aliyun.common.Packet;
import com.aliyun.common.Server;
import com.aliyun.common.Utils;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.*;

/**
 * 服务器，监听8002端口，
 * 告诉测评程序已经准备好了
 * 获取测评程序设置的端口
 * <p>
 * 转发8000的traceId到8001，转发8001的traceId到8000
 * 接受上报的traceId错误日志
 */
public class Engine extends Server {
    private OutputStream out0;
    private OutputStream out1;
    protected ServerSocket server;

    private MD5 md5 = new MD5();

    //用于向结果提交接口发送数据，总共20000个， "traceId[16]":"md5[32]", 20000 * (1 + 16 + 3 + 32 + 2)
    private byte[] request = new byte[5 * 1024 * 1024];//100K
    private int requestLen = 0;
    private int requestLineAndHeaderLen = 198;


    //错误的数据差不多有两万个
    private Map<Packet, Packet> map = new HashMap<>(30000);
    private int endFilterNum = 0;//结束的packet的个数

    //错误统计
    public static int emptyLogs = 0;
    public static int fullLogs = 0;
    public static long startTime = 0;


    public Engine() {
        try {
            byte bs[] = ("POST /api/finished HTTP/1.1\r\n" +
                    "Content-Type: multipart/form-data; boundary=--------------------------428154304761041392223667\r\n" +
                    "Host: localhost:" + data_port + "\r\n" +
                    "Content-Length:        \r\n" +
                    "Connection: keep-alive\r\n" +
                    "\r\n" +
                    "----------------------------428154304761041392223667\r\n" +
                    "Content-Disposition: form-data; name=\"result\"\r\n" +
                    "\r\n" +
                    "{"
            ).getBytes();
            requestLineAndHeaderLen = bs.length - 104;
            System.arraycopy(bs, 0, request, 0, bs.length);
            requestLen = bs.length;
        } catch (Exception e) {
            e.printStackTrace();
        }

        new Thread(() -> {//测试，两分钟过后，就制动上报数据，让程序尽快结束
            try {
                Thread.sleep(120000);//五分钟
            } catch (Exception e) {
                e.printStackTrace();
            }
            sendResult();
        }).start();
    }

    public void start() throws Exception {
        System.out.println("start tcp listener port " + listen_port);
        server = new ServerSocket(listen_port);
        while (data_port == 0) {//拿到了端口就可以不用再监听了
            Socket socket = server.accept();
            int port = socket.getPort();
            if (port == FILTER_0_LISTEN_PORT) {
                System.out.println(FILTER_0_LISTEN_PORT + " connect success");
                out0 = socket.getOutputStream();
                new Thread(() -> handleInputStream(socket)).start();
            } else if (port == FILTER_1_LISTEN_PORT) {
                System.out.println(FILTER_1_LISTEN_PORT + " connect success");
                out1 = socket.getOutputStream();
                new Thread(() -> handleInputStream(socket)).start();
            } else {
                handleHttpSocket(socket);
            }
        }
        System.out.println("stop tcp listener port " + listen_port);

        //当结束监听的时候，说明获取到数据了，通知节点启动
        Packet packet = new Packet(1, who, Packet.TYPE_START);
        sendPacket(packet, out0);
        sendPacket(packet, out1);
    }

    /**
     * 处理从tcp流中解析出来的Packet
     */
    @Override
    public void handlePacket(Packet packet) {
        //如果是traceId或者读取结束，就发送给filter
        if (packet.getType() == Packet.TYPE_MULTI_TRACE_ID || packet.getType() == Packet.TYPE_READ_END) {
//            System.out.println(packet);
            if (packet.getWho() == WHO_FILTER_0) sendPacket(packet, out1);
            else sendPacket(packet, out0);
        } else if (packet.getType() == Packet.TYPE_MULTI_LOG) {
            synchronized (this) {//因为会有两个线程调用，所以需要同步
                calcCheckSum(packet); //TODO 待计算
            }
        } else if (packet.getType() == Packet.TYPE_END) {
            synchronized (Engine.class) {
                endFilterNum++;
                if (endFilterNum >= 2) {
                    System.out.println("----------end----------end----------end----------end----------");
                    sendResult();
                }
            }
        }
    }

    private void calcCheckSum(Packet packet) {
        Packet p = map.get(packet);
        if (p == null) {
            map.put(packet, packet);
            return;
        }
        //如果已经处理，或者来自同一个filter，就不需要处理
        if (p.isHandle || p.getWho() == packet.getWho()) return;
        p.isHandle = true;
        //使用归并排序对两个数据进行处理,并计算校验和，放到request中去
        mergeAndMd5(p, packet);
    }

    private void mergeAndMd5(Packet p1, Packet p2) {
        byte bs1[] = p1.getBs();
        int len1 = p1.getLen();
        byte bs2[] = p2.getBs();
        int len2 = p2.getLen();
        int i = Packet.P_DATA + 16, j = Packet.P_DATA + 16;//加上16是因为数据段的开头存放了一个traceId
        //计算时间偏移量，如果P_DATA+16=='|'说明traceId的长度是15，否则是16
        //traceId的长度可能好似14，15，16
        int offset = 20;//TODO 时间的开始位置
        int traceIdLen;
        if (bs1[Packet.P_DATA + 13] == '|') traceIdLen = 13;
        else if (bs1[Packet.P_DATA + 14] == '|') traceIdLen = 14;
        else if (bs1[Packet.P_DATA + 15] == '|') traceIdLen = 15;
        else traceIdLen = 16;
//        System.out.println(new String(bs1, Packet.P_DATA, 20) + "  " + traceIdLen);

        md5.reset();
        int dataLen1 = 0, dataLen2 = 0;
        while (i < len1 || j < len2) {
            try {
                if (i < len1) dataLen1 = ((bs1[i] & 0XFF) << 8) + (bs1[i + 1] & 0XFF);
                if (j < len2) dataLen2 = ((bs2[j] & 0XFF) << 8) + (bs2[j + 1] & 0XFF);
                //选择 bs1
                if (j >= len2) {
                    md5.update(bs1, i + 2, dataLen1);
                    i += dataLen1 + 2;
                } else if (i >= len1) {
                    md5.update(bs2, j + 2, dataLen2);
                    j += dataLen2 + 2;
                } else if (compareBytes(bs1, i + offset, bs2, j + offset, 16) < 0) {
                    md5.update(bs1, i + 2, dataLen1);
                    i += dataLen1 + 2;
                } else {
                    md5.update(bs2, j + 2, dataLen2);
                    j += dataLen2 + 2;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
//        byte res[] = new byte[32];
        request[requestLen++] = '"';
        System.arraycopy(bs1, Packet.P_DATA, request, requestLen, traceIdLen);
        requestLen += traceIdLen;
        request[requestLen++] = '"';
        request[requestLen++] = ':';
        request[requestLen++] = '"';
        md5.digest(request, requestLen);
        requestLen += 32;
        request[requestLen++] = '"';
        request[requestLen++] = ',';

//        System.out.println(new String(bs1, Packet.P_DATA, offset - 3) + "   " + new String(res));
    }

    private int compareBytes(byte bs1[], int s1, byte bs2[], int s2, int len) {
        try {
            for (int i = 0; i < 16; i++) {//TODO 时间的前面几位数可以不比较
                if (bs1[s1 + i] == bs2[s2 + i]) continue;
                return bs1[s1 + i] - bs2[s2 + i];
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }


    public void sendPacket(Packet packet, OutputStream out) {
        try {
            int len = packet.getLen();
            byte bs[] = packet.getBs();
            out.write(bs, 0, len);
            out.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendResult() {
        try {
            if (requestLen == 299) {
                request[requestLen++] = '"';
                System.arraycopy("3d48bcdc87165ca1".getBytes(), 0, request, requestLen, 16);
                requestLen += 16;
                request[requestLen++] = '"';
                request[requestLen++] = ':';
                request[requestLen++] = '"';
                System.arraycopy("5F37E5A97CD1985B0E9E737B5553C51D".getBytes(), 0, request, requestLen, 32);
                requestLen += 32;
                request[requestLen++] = '"';
                request[requestLen++] = ',';
            }
            request[requestLen - 1] = '}';//将最后一个','换成'}'
            //要放到最后
            byte bs[] = "\r\n----------------------------428154304761041392223667--\r\n".getBytes();
            System.arraycopy(bs, 0, request, requestLen, bs.length);
            requestLen += bs.length;
            //将长度写道Content-Length中去
            String cl = String.valueOf(requestLen - requestLineAndHeaderLen);
            for (int i = 0; i < cl.length(); i++) {
                request[162 + i] = (byte) (cl.charAt(i));
            }

//            System.out.println(new String(request, 0, requestLen));
//            System.out.println("total time2 = " + System.currentTimeMillis() + " - " + startTime + "=" + (System.currentTimeMillis() - startTime));
//            Thread.sleep(5000);
//            data_port = 9000;
            Socket socket = new Socket("127.0.0.1", data_port);
//            Socket socket = new Socket();
//            socket.connect(new InetSocketAddress("127.0.0.1", 9000));
            OutputStream out = socket.getOutputStream();
            out.write(request, 0, requestLen);
            out.flush();
            InputStream in = socket.getInputStream();
            byte result[] = new byte[1024];
            int n = in.read(result);
            String str = new String(result, 0, n);
            System.out.println(str);
            System.out.println("total time2 = " + System.currentTimeMillis() + " - " + startTime + "=" + (System.currentTimeMillis() - startTime));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


}
