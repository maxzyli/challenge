if   [  $SERVER_PORT == 8002  ];
then
   /usr/local/src/jre-9.0.4/bin/java -Xms2024m -Xmn1024m -XX:CompileThreshold=1000 -jar /challenge-0.0.1-SNAPSHOT.jar $SERVER_PORT &
else
   /usr/local/src/jre-9.0.4/bin/java -Xms2500m -Xmn1024m -XX:CompileThreshold=1000 -jar /challenge-0.0.1-SNAPSHOT.jar $SERVER_PORT &
fi
tail -f /usr/local/src/start.sh