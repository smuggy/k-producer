package net.podspace.management;

import javax.management.NotCompliantMBeanException;
import javax.management.StandardMBean;

public class MBeanContainer extends StandardMBean {
    private String name;

    public <T> MBeanContainer(T implementation, Class<T> mbeanInterface)
            throws NotCompliantMBeanException {
        super(implementation, mbeanInterface);
        name = null;
    }

    public String objectName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
