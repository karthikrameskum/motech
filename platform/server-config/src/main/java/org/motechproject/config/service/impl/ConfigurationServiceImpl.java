package org.motechproject.config.service.impl;


import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.collections.Predicate;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.motechproject.commons.api.MotechException;
import org.motechproject.commons.api.MotechMapUtils;
import org.motechproject.config.core.MotechConfigurationException;
import org.motechproject.config.core.domain.BootstrapConfig;
import org.motechproject.config.core.domain.ConfigLocation;
import org.motechproject.config.core.domain.ConfigSource;
import org.motechproject.config.core.service.CoreConfigurationService;
import org.motechproject.config.domain.ModulePropertiesRecord;
import org.motechproject.config.service.BundlePropertiesService;
import org.motechproject.config.service.ConfigurationService;
import org.motechproject.server.config.domain.MotechSettings;
import org.motechproject.server.config.domain.SettingsRecord;
import org.motechproject.server.config.service.ConfigLoader;
import org.motechproject.server.config.service.SettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.motechproject.config.core.filters.ConfigFileFilter.isPlatformCoreConfigFile;

/**
 * Default implementation of {@link org.motechproject.config.service.ConfigurationService}.
 */
@Service("configurationService")
public class ConfigurationServiceImpl implements ConfigurationService {
    private static final String STRING_FORMAT = "%s/%s";
    private static final Logger LOGGER = Logger.getLogger(ConfigurationServiceImpl.class);

    private ConfigLoader configLoader;
    private ConfigSource configSource;
    private ResourceLoader resourceLoader;
    private CoreConfigurationService coreConfigurationService;
    private BundlePropertiesService bundlePropertiesService;
    private SettingService settingService;

    private Properties defaultConfig;
    private Properties configAnnotation;

    public ConfigurationServiceImpl() {
    }

    @Autowired
    public ConfigurationServiceImpl(CoreConfigurationService coreConfigurationService,
                                    SettingService settingService, BundlePropertiesService bundlePropertiesService,
                                    ConfigLoader configLoader, ResourceLoader resourceLoader) {
        this.coreConfigurationService = coreConfigurationService;
        this.settingService = settingService;
        this.configLoader = configLoader;
        this.resourceLoader = resourceLoader;
        this.bundlePropertiesService = bundlePropertiesService;
    }

    @Override
    public BootstrapConfig loadBootstrapConfig() {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Loading bootstrap configuration.");
        }

        final BootstrapConfig bootstrapConfig;

        try {
            bootstrapConfig = coreConfigurationService == null ? null : coreConfigurationService.loadBootstrapConfig();
        } catch (MotechConfigurationException e) {
            return null;
        }

        if (null != bootstrapConfig) {
            configSource = bootstrapConfig.getConfigSource();
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("BootstrapConfig:" + bootstrapConfig);
        }

        return bootstrapConfig;
    }

    @Override
    public ConfigSource getConfigSource() {
        return configSource;
    }

    @Override
    public void save(BootstrapConfig bootstrapConfig) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Saving bootstrap configuration.");
        }

        coreConfigurationService.saveBootstrapConfig(bootstrapConfig);

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Saved bootstrap configuration:" + bootstrapConfig);
        }

    }

    @Override
    @Caching(cacheable = {@Cacheable(value = SETTINGS_CACHE_NAME, key = "#root.methodName") })
    public MotechSettings getPlatformSettings() {
        if (settingService == null) {
            return null;
        }
        SettingsRecord settings = getSettings();
        settings.mergeWithDefaults(defaultConfig);
        return settings;
    }

    @Override
    public void savePlatformSettings(Properties settings) {
        SettingsRecord dbSettings = getSettings();

        dbSettings.setPlatformInitialized(true);
        dbSettings.setLastRun(DateTime.now());
        dbSettings.updateFromProperties(settings);

        dbSettings.removeDefaults(defaultConfig);

        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            dbSettings.setConfigFileChecksum(new String(digest.digest(
                    MapUtils.toProperties(dbSettings.asProperties()).toString().getBytes())));
        } catch (NoSuchAlgorithmException e) {
            throw new MotechException("MD5 algorithm not available", e);
        }

        addOrUpdateSettings(dbSettings);
    }

    @Override
    public void savePlatformSettings(MotechSettings settings) {
        savePlatformSettings(settings.asProperties());
    }

    @Override
    public void setPlatformSetting(final String key, final String value) {
        SettingsRecord dbSettings = getSettings();

        dbSettings.savePlatformSetting(key, value);

        dbSettings.removeDefaults(defaultConfig);

        addOrUpdateSettings(dbSettings);
    }

    @Override
    public void evictMotechSettingsCache() {
        // Left blank.
        // Annotation will automatically remove all cached motech settings
    }

    @Override
    public FileInputStream createZipWithConfigFiles(String propertyFile, String fileName) throws IOException {

        File file = new File(propertyFile);
        Properties properties = getSettings().asProperties();
        ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(fileName));

        try (BufferedWriter out = new BufferedWriter(new FileWriter(file))) {
            if (!properties.isEmpty()) {
                StringBuilder stringBuilder = new StringBuilder();

                for (Map.Entry<Object, Object> configProperty : properties.entrySet()) {
                    stringBuilder.append("#")
                            .append(configAnnotation.getProperty(configProperty.getKey().toString()))
                            .append("\n");

                    if (defaultConfig.containsKey(configProperty.getKey())
                            && !"".equals(defaultConfig.getProperty(configProperty.getKey().toString()))) {
                        stringBuilder.append("#Default value:\n" + "#")
                                .append(configProperty.getKey())
                                .append("=")
                                .append(defaultConfig.getProperty(configProperty.getKey().toString()))
                                .append("\n");
                    }

                    stringBuilder.append("\n")
                            .append(configProperty.getKey())
                            .append("=")
                            .append(configProperty.getValue())
                            .append("\n\n");
                }

                out.write(stringBuilder.toString());
            }
        } finally {
            if (!properties.isEmpty()) {
                zipOutputStream.putNextEntry(new ZipEntry(propertyFile));
                IOUtils.copy(new FileInputStream(file), zipOutputStream);
                zipOutputStream.closeEntry();
            }

            zipOutputStream.close();
        }

        return new FileInputStream(fileName);
    }

    /**
     * Returns properties for module matching bundle and file name. Returned properties are merged with default properties.
     *
     * @param bundle  the bundle to retrieve properties for
     * @param filename the resource filename
     * @param defaultProperties the default properties of the bundle
     * @return properties for given bundle and file name
     */
    public Properties getBundleProperties(String bundle, String filename, Properties defaultProperties) throws IOException {
        ModulePropertiesRecord record;
        Properties properties;

        record = getBundlePropertiesRecord(bundle, filename);
        if (record != null) {
            properties = MapUtils.toProperties(record.getProperties());
        } else {
            properties = new Properties();
        }

        return MapUtils.toProperties(MotechMapUtils.mergeMaps(properties, defaultProperties));
    }

    @Override
    public Map<String, Properties> getAllBundleProperties(String bundle, Map<String, Properties> allDefaultProperties) throws IOException {
        Map<String, Properties> allProperties = new HashMap<>();

        if (ConfigSource.UI.equals(configSource)) {
            List<String> filenameList = getFileNameList(bundlePropertiesService.findByBundle(bundle));
            if (filenameList == null) {
                return allDefaultProperties;
            }

            for (String filename : filenameList) {
                allProperties.put(filename, getBundleProperties(bundle, filename, allDefaultProperties.get(filename)));
            }
            return allProperties;
        } else if (ConfigSource.FILE.equals(configSource)) {
            File dir = new File(getBundleConfigDir(bundle));

            if (dir.exists()) {
                File[] files = dir.listFiles(new FileFilter() {
                    @Override
                    public boolean accept(File pathname) {
                        return pathname.isFile() && pathname.getName().endsWith(".properties");
                    }
                });

                for (File file : files) {
                    allProperties.put(file.getName(), getBundleProperties(bundle, file.getName(),
                            allDefaultProperties.get(file.getName())));
                }
            }
            return allProperties;
        }

        return allDefaultProperties;
    }

    @Override
    public void addOrUpdateProperties(String bundle, String version, String filename, Properties newProperties, Properties defaultProperties) throws IOException {
        Properties toPersist;
        if (ConfigSource.UI.equals(configSource)) {
            //Persist only non-default properties in database
            toPersist = new Properties();
            for (Map.Entry<Object, Object> entry : newProperties.entrySet()) {
                if (!defaultProperties.containsKey(entry.getKey()) ||
                        (!defaultProperties.get(entry.getKey()).equals(newProperties.get(entry.getKey())))) {
                    toPersist.put(entry.getKey(), entry.getValue());
                }
            }
        } else {
            toPersist = newProperties;
            checkDifferencesAndSaveFile(bundle, filename, toPersist);
        }
        ModulePropertiesRecord properties = new ModulePropertiesRecord(toPersist, bundle, version, filename, false);
        if (bundlePropertiesService != null) {
            addOrUpdateBundleRecord(properties);
        }
    }

    @Override
    public void updatePropertiesAfterReinstallation(String bundle, String version, String filename, Properties defaultProperties, Properties newProperties) throws IOException {
        if (!registersProperties(bundle, filename)) {
            addOrUpdateProperties(bundle, version, filename, newProperties, defaultProperties);
            return;
        }
        if (ConfigSource.UI.equals(configSource)) {
            Properties oldProperties = getBundleProperties(bundle, filename, defaultProperties);
            //Persist only non-default properties in database
            Properties toPersist = new Properties();
            Properties tempPropreties = (Properties) newProperties.clone();
            for (Map.Entry<Object, Object> entry : oldProperties.entrySet()) {
                if (newProperties.containsKey(entry.getKey())) {
                    tempPropreties.put(entry.getKey(), entry.getValue());
                }
            }

            for (Map.Entry<Object, Object> entry : tempPropreties.entrySet()) {
                if (!defaultProperties.containsKey(entry.getKey()) ||
                        (!defaultProperties.get(entry.getKey()).equals(tempPropreties.get(entry.getKey())))) {
                    toPersist.put(entry.getKey(), entry.getValue());
                }
            }

            ModulePropertiesRecord properties = new ModulePropertiesRecord(toPersist, bundle, version, filename, false);
            deleteByBundleAndFileName(bundle, filename);
            addOrUpdateBundleRecord(properties);
        } else if (ConfigSource.FILE.equals(configSource)) {
            if  (registersProperties(bundle, filename)) {
                return;
            }
            Properties currentProperties = getBundleProperties(bundle, filename, defaultProperties);
            Properties toStore = MotechMapUtils.asProperties(MotechMapUtils.mergeMaps(currentProperties, newProperties));
            checkDifferencesAndSaveFile(bundle, filename, toStore);
        }
    }

    private void checkDifferencesAndSaveFile(String bundle, String filename, Properties toStore) throws IOException {
        File file = new File(String.format(STRING_FORMAT, getBundleConfigDir(bundle), filename));
        boolean saveFile = false;
        setUpDirsForFile(file);
        if (file.exists()) {
            try (FileInputStream fileInputStream = new FileInputStream(file)) {
                Properties fromFile = new Properties();
                fromFile.load(fileInputStream);
                if (!fromFile.equals(toStore)) {
                    saveFile =  true;
                }
            }
        } else {
            saveFile = true;
        }

        if (saveFile) {
            try (FileOutputStream fileOutputStream = new FileOutputStream(file)) {
                toStore.store(fileOutputStream, null);
            }
        }
    }

    @Override
    public void removeAllBundleProperties(String bundle) {
        if (ConfigSource.UI.equals(configSource)) {
            deleteByBundle(bundle);
        } else if (ConfigSource.FILE.equals(configSource)) {
            File dir = new File(getBundleConfigDir(bundle));
            try {
                FileUtils.deleteDirectory(dir);
            } catch (IOException e) {
                throw new MotechConfigurationException("Could not delete configuration file", e);
            }
        }
    }

    @Override
    public void processExistingConfigs(List<File> files) {
        if (bundlePropertiesService == null) {
            LOGGER.warn("Unable to retrieve bundle properties ");
            return;
        }

        List<ModulePropertiesRecord> records = new ArrayList<>();
        List<ModulePropertiesRecord> dbRecords = bundlePropertiesService.retrieveAll();

        for (File file : files) {
            if (isPlatformCoreConfigFile(file)) {
                savePlatformSettings(loadConfig());
                continue;
            }

            final ModulePropertiesRecord record = ModulePropertiesRecord.buildFrom(file);
            if (record == null) {
                continue;
            }
            ModulePropertiesRecord dbRecord = (ModulePropertiesRecord) CollectionUtils.find(dbRecords, new Predicate() {
                @Override
                public boolean evaluate(Object object) {
                    return record.sameAs(object);
                }
            });
            if (dbRecord != null) {
                dbRecords.remove(dbRecord);
            }
            records.add(record);
        }

        if (CollectionUtils.isNotEmpty(records)) {
            addOrUpdateBundleRecords(records);
        }

        if (CollectionUtils.isNotEmpty(dbRecords)) {
            removeBundleRecords(dbRecords);
        }
    }

    @Override
    public void addOrUpdate(File file) {
        if (isPlatformCoreConfigFile(file)) {
            savePlatformSettings(loadConfig());
            return;
        }

        addOrUpdateBundleRecord(ModulePropertiesRecord.buildFrom(file));
    }

    private SettingsRecord loadSettingsFromStream(org.springframework.core.io.Resource motechSettings) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");

            try (DigestInputStream dis = new DigestInputStream(motechSettings.getInputStream(), digest)) {
                //load configFileSettings and calculate MD5 hash
                SettingsRecord settingsRecord = new SettingsRecord();
                settingsRecord.load(dis);
                settingsRecord.setConfigFileChecksum(new String(digest.digest()));
                return settingsRecord; // startup loaded
            } catch (IOException e) {
                throw new MotechException("Error loading configuration", e);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new MotechException("MD5 algorithm not available", e);
        }
    }

    @Override
    public void saveRawConfig(String bundle, String version, String filename, InputStream rawData) throws IOException {
        if (ConfigSource.UI.equals(configSource)) {
            Properties p = new Properties();
            p.put("rawData", IOUtils.toString(rawData));
            ModulePropertiesRecord record = new ModulePropertiesRecord(p, bundle, version, filename, true);
            addOrUpdateBundleRecord(record);
        } else if (ConfigSource.FILE.equals(configSource)) {
            File file = new File(String.format("%s/raw/%s", getBundleConfigDir(bundle), filename));
            setUpDirsForFile(file);

            try (FileOutputStream fos = new FileOutputStream(file)) {
                IOUtils.copy(rawData, fos);
            }
        }
    }

    @Override
    public List<String> retrieveRegisteredBundleNames() {
        List<String> bundleNames = new ArrayList<>();
        if (ConfigSource.UI.equals(configSource)) {
            List<ModulePropertiesRecord> allRecords = bundlePropertiesService.retrieveAll();
            for (ModulePropertiesRecord rec : allRecords) {
                bundleNames.add(rec.getBundle());
            }
        } else if (ConfigSource.FILE.equals(configSource)) {
            File configDir = new File(getConfigDir());
            File[] dirs = configDir.listFiles(new FileFilter() {
                @Override
                public boolean accept(File pathname) {
                    return pathname.isDirectory();
                }
            });

            if (dirs != null) {
                for (File dir : dirs) {
                    bundleNames.add(dir.getName());
                }
            }
        }
        return bundleNames;
    }

    @Override
    public List<String> listRawConfigNames(String bundle) {
        List<String> fileNames = new ArrayList<>();
        if (ConfigSource.UI.equals(configSource)) {
            List<ModulePropertiesRecord> records = bundlePropertiesService.findByBundle(bundle);
            for (ModulePropertiesRecord rec : records) {
                if (rec.isRaw()) {
                    fileNames.add(rec.getFilename());
                }
            }
        } else if (ConfigSource.FILE.equals(configSource)) {
            File configDir = new File(getBundleConfigDir(bundle) + "/raw");

            File[] files = configDir.listFiles(new FileFilter() {
                @Override
                public boolean accept(File pathname) {
                    return !pathname.isDirectory();
                }
            });

            if (files != null) {
                for (File file : files) {
                    fileNames.add(file.getName());
                }
            }
        }
        return fileNames;
    }

    @Override
    public InputStream getRawConfig(String bundle, String filename, Resource resource) throws IOException {
        if (ConfigSource.UI.equals(configSource)) {
            ModulePropertiesRecord rec = getBundlePropertiesRecord(bundle, filename);
            if (rec.isRaw()) {
                return IOUtils.toInputStream(rec.getProperties().get("rawData").toString());
            } else {
                return null;
            }
        } else if (ConfigSource.FILE.equals(configSource)) {
            File file = new File(String.format("%s/raw/%s", getBundleConfigDir(bundle), filename));

            InputStream is = null;
            if (file.exists()) {
                is = new FileInputStream(file);
            }

            return is;
        } else {
            return resource == null ? null : resource.getInputStream();
        }
    }

    @Override
    public boolean registersProperties(String bundle, String filename) {
        this.loadBootstrapConfig();
        if (ConfigSource.UI.equals(configSource)) {
            ModulePropertiesRecord rec = getBundlePropertiesRecord(bundle, filename);
            return rec != null;
        } else {
            File file = new File(String.format(STRING_FORMAT, getBundleConfigDir(bundle), filename));
            return file.exists();
        }
    }

    @Override
    public void updateConfigLocation(String newConfigLocation) {
        try {
            coreConfigurationService.addConfigLocation(newConfigLocation);
        } catch (java.nio.file.FileSystemException e) {
            throw new MotechConfigurationException("Cannot add and/or update file monitoring location", e);
        }
    }

    @Override
    public void deleteByBundle(String bundle) {
        List<ModulePropertiesRecord> records = bundlePropertiesService.findByBundle(bundle);
        for (ModulePropertiesRecord record : records) {
            bundlePropertiesService.delete(record);
        }
    }

    @Override
    public void deleteByBundleAndFileName(String bundle, String filename) {
        List<ModulePropertiesRecord> records = bundlePropertiesService.findByBundleAndFileName(bundle, filename);
        for (ModulePropertiesRecord record : records) {
            bundlePropertiesService.delete(record);
        }
    }

    @Override
    public boolean rawConfigExists(String bundle, String filename) {
        if (ConfigSource.UI.equals(configSource)) {
            ModulePropertiesRecord rec = getBundlePropertiesRecord(bundle, filename);
            return (rec != null) && rec.isRaw();
        } else if (configSource != null && ConfigSource.FILE.equals(configSource)) {
            File file = new File(String.format("%s/raw/%s", getBundleConfigDir(bundle), filename));
            return file.exists();
        }
        return false;
    }

    private String getConfigDir() {
        if (coreConfigurationService == null) {
            return System.getProperty("user.home") + "/config";
        }
        return coreConfigurationService.getConfigLocation().getLocation();
    }

    private String getBundleConfigDir(String bundle) {
        return String.format("%s/%s/", getConfigDir(), bundle);
    }

    private static void setUpDirsForFile(File file) {
        file.getParentFile().mkdirs();
    }

    @Override
    public SettingsRecord loadDefaultConfig() {
        SettingsRecord settingsRecord = null;
        org.springframework.core.io.Resource defaultSettings = resourceLoader.getResource("classpath:motech-settings.properties");
        if (defaultSettings != null) {
            settingsRecord = loadSettingsFromStream(defaultSettings);
        }

        return settingsRecord;
    }

    @Override
    public SettingsRecord loadConfig() {
        return configLoader.loadMotechSettings();
    }

    @Override
    public boolean requiresConfigurationFiles() {
        try {
            if (getConfigSource() == null) {
                configSource = loadBootstrapConfig().getConfigSource();
            }
            if (!configSource.isFile()) {
                return false;
            }
            ConfigLocation configLocation = coreConfigurationService.getConfigLocation();
            return !configLocation.hasPlatformConfigurationFile();
        } catch (MotechConfigurationException ex) {
            LOGGER.error(ex.getMessage(), ex);
            return true;
        }
    }

    void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @javax.annotation.Resource(name = "defaultSettings")
    public void setDefaultConfig(Properties defaultConfig) {
        this.defaultConfig = defaultConfig;
    }

    @javax.annotation.Resource(name = "defaultAnnotations")
    public void setConfigAnnotation(Properties configAnnotation) {
        this.configAnnotation = configAnnotation;
    }

    /**
     * Returns a list of file names stored in given records.
     *
     * @param records the records to be searched
     * @return list of file names
     */
    List<String> getFileNameList(List<ModulePropertiesRecord> records) {
        if (records.isEmpty()) {
            return null;
        }

        List<String> foundFiles = new ArrayList<>();
        for (ModulePropertiesRecord rec : records) {
            foundFiles.add(rec.getFilename());
        }
        return foundFiles;
    }

    @Override
    public void addOrUpdateBundleRecord(ModulePropertiesRecord record) {
        ModulePropertiesRecord rec = getBundlePropertiesRecord(record.getBundle(), record.getFilename());
        if (rec == null) {
            bundlePropertiesService.create(record);
        } else {
            rec.setProperties(record.getProperties());
            bundlePropertiesService.update(rec);
        }
    }

    @Override
    public void addOrUpdateBundleRecords(List<ModulePropertiesRecord> records) {
        for (ModulePropertiesRecord rec : records) {
            addOrUpdateBundleRecord(rec);
        }
    }

    @Override
    public void removeBundleRecords(List<ModulePropertiesRecord> records) {
        for (ModulePropertiesRecord rec : records) {
            bundlePropertiesService.delete(rec);
        }
    }

    public SettingsRecord getSettings() {
        SettingsRecord settingRecord = settingService.retrieve("id", 1);
        return (settingRecord == null ? new SettingsRecord() :
                settingRecord);
    }

    /**
     * Adds given settings to the settings service. If they are present, they will be updated.
     *
     * @param settingsRecord  the settings to be add
     */
    public void addOrUpdateSettings(SettingsRecord settingsRecord) {
        SettingsRecord record = settingService.retrieve("id", 1);
        if (record == null) {
            settingService.create(settingsRecord);
        } else {
            record.setConfigFileChecksum(settingsRecord.getConfigFileChecksum());
            record.setFilePath(settingsRecord.getFilePath());
            record.setPlatformInitialized(settingsRecord.isPlatformInitialized());
            record.setPlatformSettings(settingsRecord.getPlatformSettings());
            settingService.update(record);
        }
    }

    /**
     * Returns {@code ModulePropertiesRecord} for a module with given bundle and file name.
     *
     * @param bundle  the modules bundle
     * @param filename  the name of the file
     * @return the {@code ModulePropertiesRecord} matching given information
     */
    ModulePropertiesRecord getBundlePropertiesRecord(String bundle, String filename) {
        List<ModulePropertiesRecord>  records = (bundlePropertiesService == null) ? null :
                bundlePropertiesService.findByBundleAndFileName(bundle, filename);
        if (records != null) {
            for (ModulePropertiesRecord rec : records) {
                if (rec.getFilename().equals(filename)) {
                    return rec;
                }
            }
        }
        return null;
    }
}
