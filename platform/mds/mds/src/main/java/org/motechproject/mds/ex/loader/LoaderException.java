package org.motechproject.mds.ex.loader;

/**
 * The <code>LoaderException</code> exception signals situations in which there were problems with
 * correct loading the given class or its dependencies.
 */
public class LoaderException extends RuntimeException {

    public LoaderException(Throwable cause) {
        super(cause);
    }

}
