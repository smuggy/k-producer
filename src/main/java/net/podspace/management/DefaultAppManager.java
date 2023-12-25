package net.podspace.management;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultAppManager implements ApplicationManager {
    private static final Logger logger = LoggerFactory.getLogger(DefaultAppManager.class);
    ManagementAction exitAction;

    public DefaultAppManager(ManagementAction exitAction) {
        logger.info("Creating appmanager...");
        this.exitAction = exitAction;
    }

    @Override
    public void exitApplication() {
        logger.info("doing action...");
        exitAction.doAction();
        JMXService.stopServer();
    }

    public void setExitAction(ManagementAction exitAction) {
        this.exitAction = exitAction;
    }

}
