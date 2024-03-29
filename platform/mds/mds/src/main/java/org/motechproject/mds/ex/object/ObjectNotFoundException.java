package org.motechproject.mds.ex.object;

import org.motechproject.mds.ex.MdsException;

/**
 * Signals that the expected object was not found in the database.
 */
public class ObjectNotFoundException extends MdsException {

    private static final long serialVersionUID = 478668621463294074L;

    public ObjectNotFoundException() {
        super("mds.error.objectNotFound");
    }
}
