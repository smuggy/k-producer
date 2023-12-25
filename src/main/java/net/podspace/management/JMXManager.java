package net.podspace.management;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JMXManager {
    private static final Logger logger = LoggerFactory.getLogger(JMXManager.class);

    public static void createDefaultManagement(ManagementAction action) {
        ManagementAgent agent = new ManagementAgentImpl();
        ApplicationManager manager = new DefaultAppManager(action);
        try {
            MBeanContainer mbc = new MBeanContainer(manager, ApplicationManager.class);
            mbc.setName("name=exitAction");
            agent.addBean(mbc);
        } catch (Exception e) {
            logger.info("exception registering mbean...", e);
        }
    }
}
