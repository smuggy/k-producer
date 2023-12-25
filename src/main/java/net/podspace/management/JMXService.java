package net.podspace.management;

import java.lang.management.ManagementFactory;
import java.rmi.registry.LocateRegistry;
import java.util.HashMap;

import javax.management.MBeanServer;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JMXService {
    private static final Logger logger = LoggerFactory.getLogger(JMXService.class);
    private static JMXConnectorServer connectorServer;
    private static final int port = 9700;
    private static final int rmiPort = 9701;
    private static final String JMX_REMOTE = "com.sun.management.jmxremote";
    private static final String JMX_REMOTE_SSL = "com.sun.management.jmxremote.ssl";
    private static final String JMX_REMOTE_LOCAL_ONLY = "com.sun.management.jmxremote.local.only";
    private static final String JMX_REMOTE_AUTHENTICATE = "com.sun.management.jmxremote.authenticate";
    private static final String JMX_URL_BEG_STRING = "service:jmx:rmi://0.0.0.0:";
    private static final String JMX_URL_MID_STRING = "/jndi/rmi://0.0.0.0:";
    private static final String JMX_URL_END_STRING = "/jmxrmi";

    JMXService() {
        // initiate();
    }

    static void initiate() {
        try {
//            if (alreadySetup())
//                return;
            logger.info("initiating on port " + port + "...");
            LocateRegistry.createRegistry(rmiPort);
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
//            HashMap<String, Object> env = new HashMap<>();
//            env.put(JMX_REMOTE, true);
//            env.put(JMX_REMOTE_SSL, false);
//            env.put(JMX_REMOTE_LOCAL_ONLY, false);
//            env.put(JMX_REMOTE_AUTHENTICATE, false);
//            // SslRMIClientSocketFactory csf = new SslRMIClientSocketFactory();
//            // SslRMIServerSocketFactory ssf = new SslRMIServerSocketFactory();
//            // env.put(RMIConnectorServer.RMI_CLIENT_SOCKET_FACTORY_ATTRIBUTE,
//            // csf);
//            // env.put(RMIConnectorServer.RMI_SERVER_SOCKET_FACTORY_ATTRIBUTE,
//            // ssf);
//            String urlString = JMX_URL_BEG_STRING + rmiPort + JMX_URL_MID_STRING + port
//                    + JMX_URL_END_STRING;
//            logger.info("Setting up the jmx url: " + urlString);
//            JMXServiceURL url = new JMXServiceURL(urlString);
//            connectorServer = JMXConnectorServerFactory.newJMXConnectorServer(url, env, mbs);
//            connectorServer.start();
        } catch (Exception e) {
//            logger.warn("error occurred...", e);
//        } finally {
//            logger.info("setup done...");
          }
    }

    public static void stopServer() {
        try {
            if (connectorServer != null)
                connectorServer.stop();
        } catch (Exception e) {
            logger.info("stopServer exception...", e);
        }
    }

    private static boolean alreadySetup() {
        if (System.getProperty(JMX_REMOTE) != null) {
            logger.info("JMX already setup...");
            return true;
        }
        logger.info("JMX not setup...");
        return false;
    }
}
