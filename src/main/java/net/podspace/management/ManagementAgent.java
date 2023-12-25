package net.podspace.management;

public interface ManagementAgent {
    void addBean(MBeanContainer bean);
    void removeBean(MBeanContainer bean);
}
