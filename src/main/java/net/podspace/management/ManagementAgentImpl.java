package net.podspace.management;


import java.lang.management.ManagementFactory;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ManagementAgentImpl implements ManagementAgent {
    private static final Logger logger = LoggerFactory.getLogger(ManagementAgentImpl.class);
    private final MBeanServer mbeanServer;

    public ManagementAgentImpl() {
        mbeanServer = ManagementFactory.getPlatformMBeanServer();
    }

    @Override
    public void addBean(MBeanContainer bean) {
        ObjectName objectName;
        logger.info("Adding management for: {}", bean.objectName());
        try {
            String name = "net.podspace.jmx:type=KPAgent," + bean.objectName();
            objectName = new ObjectName(name);
            try {
                mbeanServer.unregisterMBean(objectName);
            } catch (InstanceNotFoundException inf) {
                // ignore...
            }
            mbeanServer.registerMBean(bean, objectName);
        } catch (Exception ie) {
            logger.warn("Unexpected exception when adding management bean...", ie);
        }
    }

    @Override
    public void removeBean(MBeanContainer bean) {
        ObjectName objectName;
        logger.debug("Removing management for {}", bean.objectName());
        try {
            objectName = new ObjectName("net.podspace.jmx:type=KPAgent," + bean.objectName());
            mbeanServer.unregisterMBean(objectName);
        } catch (Exception ie) {
            logger.info("Exception occurred in management agent impl:", ie);
        }

    }

}
