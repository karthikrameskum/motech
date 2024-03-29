package org.motechproject.mds.ex.entity;

/**
 * Signals that there were problems with the relations in the data model.
 */
public class InvalidRelationshipException extends RuntimeException {

    private static final long serialVersionUID = -6881864414053573403L;

    public InvalidRelationshipException(String message) {
        super(message + "\nUnresolvable, circular relationship has been detected between entities. This means that your " +
                "data model contains an error. Please check the mentioned entities and fix the problem. " +
                "If you have meant to create a bi-directional relationship, please use the @javax.jdo.annotations.Persistent " +
                "annotation, with the mappedBy parameter, on exactly ONE related field.");
    }
}
