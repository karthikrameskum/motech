package org.motechproject.mds.ex;

/**
 * Exception indicating that user supplied value is used in an instance.
 */
public class UserSuppliedComboboxValuesUsedException extends MdsException {

    private static final long serialVersionUID = -7371335286597754778L;

    private String message;

    public UserSuppliedComboboxValuesUsedException(String comboboxName, String value) {
        super("mds.error.userSuppliedValueUsed", comboboxName, value);
        message = String.format("\"Allow user supplied\" setting of field \"%s\" cannot be unchecked as user supplied value \"%s\" is used in one of the instances.", comboboxName, value);
    }

    @Override
    public String getMessage() {
        return message;
    }

}
