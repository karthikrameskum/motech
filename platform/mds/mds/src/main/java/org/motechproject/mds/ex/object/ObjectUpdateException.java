package org.motechproject.mds.ex.object;

import org.motechproject.mds.ex.MdsException;

/**
 * Signals that it was not possible to update object instance from the provided data.
 */
public class ObjectUpdateException extends MdsException {

    private static final long serialVersionUID = -6214111291407582493L;

    public ObjectUpdateException() {
        super("mds.error.objectUpdateError");
    }

    public ObjectUpdateException(Throwable cause) {
        super("mds.error.objectUpdateError", cause);
    }
}
