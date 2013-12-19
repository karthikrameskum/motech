package org.motechproject.config.repository;

import org.apache.commons.collections.MapUtils;
import org.ektorp.support.View;
import org.motechproject.commons.couchdb.dao.MotechBaseRepository;
import org.motechproject.config.domain.ModulePropertiesRecord;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The <code>AllModuleProperties</code> class is responsible for storing and retrieving
 * module properties from the database. It allows to retrieve properties by module name
 * and create/update records.
 */
@Repository
@View(name = "by_name", map = "function(doc) { if(doc.type === 'ModulePropertiesRecord') emit(doc.module); }")
public class AllModuleProperties extends MotechBaseRepository<ModulePropertiesRecord> {

    private static final String BY_NAME = "by_name";


    public AllModuleProperties() {
        super("motech-module-settings", ModulePropertiesRecord.class);
    }

    public List<ModulePropertiesRecord> byModuleName(String module) {
        List<ModulePropertiesRecord> records = queryView(BY_NAME, module);
        return records.isEmpty() ? null : records;
    }

    public ModulePropertiesRecord byModuleAndFileName(String module, String filename) {
        List<ModulePropertiesRecord> records = queryView(BY_NAME, module);
        for (ModulePropertiesRecord rec : records) {
            if (rec.getFilename().equals(filename)) {
                return rec;
            }
        }
        return null;
    }

    public List<String> retrieveFileNamesForModule(String module) {
        List<ModulePropertiesRecord> records = queryView(BY_NAME, module);
        if (records.isEmpty()) {
            return null;
        }

        List<String> foundFiles = new ArrayList<>();
        for (ModulePropertiesRecord rec : records) {
            foundFiles.add(rec.getFilename());
        }
        return foundFiles;
    }

    public Properties asProperties(String module, String filename) {
        ModulePropertiesRecord record = byModuleAndFileName(module, filename);

        return (record == null) ? null : MapUtils.toProperties(record.getProperties());
    }

    public void addOrUpdate(ModulePropertiesRecord record) {
        ModulePropertiesRecord rec = byModuleAndFileName(record.getModule(), record.getFilename());
        if (rec == null) {
            add(record);
        } else {
            rec.setProperties(record.getProperties());
            update(rec);
        }
    }


}
