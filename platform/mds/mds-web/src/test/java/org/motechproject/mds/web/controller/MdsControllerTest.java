package org.motechproject.mds.web.controller;

import org.junit.Assert;
import org.junit.Test;
import org.motechproject.mds.ex.entity.EntityAlreadyExistException;
import org.motechproject.mds.ex.entity.EntityCreationException;
import org.motechproject.mds.ex.entity.EntityInfrastructureException;
import org.motechproject.mds.ex.entity.EntityNotFoundException;
import org.motechproject.mds.ex.entity.EntityReadOnlyException;
import org.motechproject.mds.ex.MdsException;

public class MdsControllerTest {
    private MdsController controller = new MdsController() {

        @Override
        public String toString() {
            return "MdsControllerTest";
        }

    };

    private MdsException[] exceptions = {
            new EntityAlreadyExistException(),
            new EntityNotFoundException(),
            new EntityReadOnlyException(),
            new EntityCreationException(new RuntimeException()),
            new EntityInfrastructureException(new RuntimeException()),
            new MdsException("test.key")
    };

    @Test
    public void shouldHandleMdsExceptions() throws Exception {
        for (MdsException exception : exceptions) {
            Assert.assertEquals(
                    String.format("key:%s", exception.getMessageKey()),
                    controller.handleMdsException(exception)
            );
        }
    }
}
