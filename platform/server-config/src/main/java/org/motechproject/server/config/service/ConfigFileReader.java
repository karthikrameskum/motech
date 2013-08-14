package org.motechproject.server.config.service;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

public interface ConfigFileReader {
    Properties getProperties(File file) throws IOException;
}
