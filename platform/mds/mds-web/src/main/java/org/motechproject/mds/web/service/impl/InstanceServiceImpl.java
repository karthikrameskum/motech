package org.motechproject.mds.web.service.impl;

import javassist.CannotCompileException;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.reflect.FieldUtils;
import org.apache.commons.lang.reflect.MethodUtils;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.motechproject.commons.date.model.Time;
import org.motechproject.mds.dto.DtoHelper;
import org.motechproject.mds.dto.EntityDto;
import org.motechproject.mds.dto.FieldDto;
import org.motechproject.mds.dto.FieldInstanceDto;
import org.motechproject.mds.dto.LookupDto;
import org.motechproject.mds.dto.MetadataDto;
import org.motechproject.mds.dto.TypeDto;
import org.motechproject.mds.ex.entity.EntityNotFoundException;
import org.motechproject.mds.ex.entity.EntitySchemaMismatchException;
import org.motechproject.mds.ex.lookup.LookupExecutionException;
import org.motechproject.mds.ex.lookup.LookupNotFoundException;
import org.motechproject.mds.ex.object.ObjectNotFoundException;
import org.motechproject.mds.ex.object.ObjectReadException;
import org.motechproject.mds.ex.object.ObjectUpdateException;
import org.motechproject.mds.ex.object.SecurityException;
import org.motechproject.mds.filter.Filters;
import org.motechproject.mds.helper.DataServiceHelper;
import org.motechproject.mds.lookup.LookupExecutor;
import org.motechproject.mds.query.QueryParams;
import org.motechproject.mds.service.EntityService;
import org.motechproject.mds.service.HistoryService;
import org.motechproject.mds.service.MotechDataService;
import org.motechproject.mds.service.TrashService;
import org.motechproject.mds.service.TypeService;
import org.motechproject.mds.service.impl.history.HistoryTrashClassHelper;
import org.motechproject.mds.util.ClassName;
import org.motechproject.mds.util.Constants;
import org.motechproject.mds.util.MDSClassLoader;
import org.motechproject.mds.util.MemberUtil;
import org.motechproject.mds.util.PropertyUtil;
import org.motechproject.mds.util.SecurityMode;
import org.motechproject.mds.util.TypeHelper;
import org.motechproject.mds.web.domain.ComboboxHolder;
import org.motechproject.mds.web.domain.EntityRecord;
import org.motechproject.mds.web.domain.FieldRecord;
import org.motechproject.mds.web.domain.HistoryRecord;
import org.motechproject.mds.web.service.InstanceService;
import org.motechproject.osgi.web.util.WebBundleUtil;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.motechproject.mds.util.Constants.MetadataKeys.MAP_KEY_TYPE;
import static org.motechproject.mds.util.Constants.MetadataKeys.MAP_VALUE_TYPE;
import static org.motechproject.mds.util.Constants.MetadataKeys.RELATED_CLASS;
import static org.motechproject.mds.util.SecurityUtil.getUserPermissions;
import static org.motechproject.mds.util.SecurityUtil.getUsername;

/**
 * Default implementation of the {@link org.motechproject.mds.web.service.InstanceService} interface.
 */
@Service
public class InstanceServiceImpl implements InstanceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InstanceServiceImpl.class);
    private static final DateTimeFormatter DTF = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm Z");
    private static final String ID = "id";
    private static final Integer TO_STRING_MAX_LENGTH = 80;

    private EntityService entityService;
    private BundleContext bundleContext;
    private HistoryService historyService;
    private TrashService trashService;
    private TypeService typeService;

    @Override
    public Object saveInstance(EntityRecord entityRecord) {
        return saveInstance(entityRecord, null);
    }

    @Override
    public Object saveInstance(EntityRecord entityRecord, Long deleteValueFieldId) {
        EntityDto entity = getEntity(entityRecord.getEntitySchemaId());
        validateCredentials(entity);

        try {
            MotechDataService service = getServiceForEntity(entity);
            Class<?> entityClass = getEntityClass(entity);

            boolean newObject = entityRecord.getId() == null;

            Object instance;
            if (newObject) {
                instance = entityClass.newInstance();
            } else {
                instance = service.retrieve(ID, entityRecord.getId());
                if (instance == null) {
                    throw new ObjectNotFoundException();
                }
            }

            updateFields(instance, entityRecord.getFields(), service, deleteValueFieldId, !newObject);

            if (newObject) {
                return service.create(instance);
            } else {
                return service.update(instance);
            }
        } catch (Exception e) {
            LOGGER.error("Unable to save object instance", e);
            throw new ObjectUpdateException(e);
        }
    }

    @Override
    public List<EntityRecord> getEntityRecords(Long entityId) {
        return getEntityRecords(entityId, null);
    }

    @Override
    public List<EntityRecord> getEntityRecords(Long entityId, QueryParams queryParams) {
        EntityDto entity = getEntity(entityId);
        validateCredentials(entity);
        List<FieldDto> fields = entityService.getEntityFields(entityId);

        MotechDataService service = getServiceForEntity(entity);
        List instances = service.retrieveAll(queryParams);

        return instancesToRecords(instances, entity, fields, service);
    }

    @Override
    public List<FieldDto> getEntityFields(Long entityId) {
        validateCredentials(getEntity(entityId));
        return entityService.getEntityFields(entityId);
    }

    @Override
    public List<EntityRecord> getTrashRecords(Long entityId, QueryParams queryParams) {
        EntityDto entity = getEntity(entityId);
        validateCredentials(entity);

        MotechDataService service = getServiceForEntity(entity);
        List<FieldDto> fields = entityService.getEntityFields(entityId);
        Collection collection = trashService.getInstancesFromTrash(entity.getClassName(), queryParams);

        return instancesToRecords(collection, entity, fields, service);
    }

    @Override
    public long countTrashRecords(Long entityId) {
        EntityDto entity = getEntity(entityId);
        validateCredentials(entity);

        return trashService.countTrashRecords(entity.getClassName());
    }

    @Override
    public EntityRecord getSingleTrashRecord(Long entityId, Long instanceId) {
        EntityDto entity = getEntity(entityId);
        validateCredentials(entity);

        MotechDataService service = getServiceForEntity(entity);
        List<FieldDto> fields = entityService.getEntityFields(entityId);
        Object instance = trashService.findTrashById(instanceId, entityId);

        return instanceToRecord(instance, entity, fields, service);
    }

    @Override
    public Object getInstanceField(Long entityId, Long instanceId, String fieldName) {
        EntityDto entity = getEntity(entityId);
        MotechDataService service = getServiceForEntity(entity);
        validateCredentials(entity);

        Object instance = service.retrieve(ID, instanceId);

        return service.getDetachedField(instance, fieldName);
    }

    @Override
    public List<EntityRecord> getEntityRecordsFromLookup(Long entityId, String lookupName, Map<String, Object> lookupMap,
                                                         QueryParams queryParams) {
        EntityDto entity = getEntity(entityId);
        validateCredentials(entity);

        LookupDto lookup = getLookupByName(entityId, lookupName);
        List<FieldDto> fields = entityService.getEntityFields(entityId);
        Map<String, FieldDto> fieldMap = DtoHelper.asFieldMapByName(fields);

        MotechDataService service = getServiceForEntity(entity);

        try {
            LookupExecutor lookupExecutor = new LookupExecutor(service, lookup, fieldMap);

            Object result = lookupExecutor.execute(lookupMap, queryParams);

            if (lookup.isSingleObjectReturn()) {
                EntityRecord record = instanceToRecord(result, entity, fields, service);
                return (record == null) ? new ArrayList<EntityRecord>() : Arrays.asList(record);
            } else {
                List instances = (List) result;
                return instancesToRecords(instances, entity, fields, service);
            }
        } catch (Exception e) {
            throw new LookupExecutionException(e);
        }
    }

    @Override
    public List<EntityRecord> getEntityRecordsWithFilter(Long entityId, Filters filters, QueryParams queryParams) {
        EntityDto entity = getEntity(entityId);
        validateCredentials(entity);

        List<FieldDto> fields = entityService.getEntityFields(entityId);
        MotechDataService service = getServiceForEntity(entity);

        List instances = service.filter(filters, queryParams);

        return instancesToRecords(instances, entity, fields, service);
    }

    @Override
    public long countRecordsWithFilters(Long entityId, Filters filters) {
        EntityDto entity = getEntity(entityId);
        validateCredentials(entity);
        MotechDataService service = getServiceForEntity(entity);

        return service.countForFilters(filters);
    }

    @Override
    public long countRecords(Long entityId) {
        EntityDto entity = getEntity(entityId);
        validateCredentials(entity);
        MotechDataService service = getServiceForEntity(entity);

        return service.count();
    }

    @Override
    public long countRecordsByLookup(Long entityId, String lookupName, Map<String, Object> lookupMap) {
        EntityDto entity = getEntity(entityId);
        validateCredentials(entity);

        LookupDto lookup = getLookupByName(entityId, lookupName);
        List<FieldDto> fields = entityService.getEntityFields(entityId);
        Map<String, FieldDto> fieldMap = DtoHelper.asFieldMapByName(fields);

        MotechDataService service = getServiceForEntity(entity);

        try {
            LookupExecutor lookupExecutor = new LookupExecutor(service, lookup, fieldMap);
            return lookupExecutor.executeCount(lookupMap);
        } catch (Exception e) {
            throw new LookupExecutionException(e);
        }
    }

    @Override
    public void revertPreviousVersion(Long entityId, Long instanceId, Long historyId) {
        HistoryRecord historyRecord = getHistoryRecord(entityId, instanceId, historyId);
        if (!historyRecord.isRevertable()) {
            throw new EntitySchemaMismatchException();
        }
        saveInstance(new EntityRecord(instanceId, entityId, historyRecord.getFields()));
    }

    @Override
    public List<FieldInstanceDto> getInstanceFields(Long entityId, Long instanceId) {
        EntityDto entity = entityService.getEntity(entityId);
        assertEntityExists(entity);
        validateCredentials(entity);

        List<FieldDto> fields = entityService.getEntityFields(entityId);

        List<FieldInstanceDto> result = new ArrayList<>();
        for (FieldDto field : fields) {
            FieldInstanceDto fieldInstanceDto = new FieldInstanceDto(field.getId(), instanceId, field.getBasic());
            result.add(fieldInstanceDto);
        }

        return result;
    }

    @Override
    public List<HistoryRecord> getInstanceHistory(Long entityId, Long instanceId, QueryParams queryParams) {
        EntityDto entity = getEntity(entityId);
        validateCredentials(entity);
        MotechDataService service = getServiceForEntity(entity);

        Object instance = service.retrieve(ID, instanceId);

        List history = historyService.getHistoryForInstance(instance, queryParams);
        List<HistoryRecord> result = new ArrayList<>();
        for (Object o : history) {
            result.add(convertToHistoryRecord(o, entity, instanceId, service));
        }
        return result;
    }

    @Override
    public long countHistoryRecords(Long entityId, Long instanceId) {
        EntityDto entity = getEntity(entityId);
        validateCredentials(entity);
        MotechDataService service = getServiceForEntity(entity);

        Object instance = service.retrieve(ID, instanceId);

        return historyService.countHistoryRecords(instance);
    }

    @Override
    public HistoryRecord getHistoryRecord(Long entityId, Long instanceId, Long historyId) {
        EntityDto entity = getEntity(entityId);
        validateCredentials(entity);
        MotechDataService service = getServiceForEntity(entity);

        Object instance = service.retrieve(ID, instanceId);

        Object historyInstance = historyService.getSingleHistoryInstance(instance, historyId);

        return convertToHistoryRecord(historyInstance, entity, instanceId, service);
    }

    @Override
    public EntityRecord newInstance(Long entityId) {
        validateCredentials(getEntity(entityId));
        List<FieldDto> fields = entityService.getEntityFields(entityId);
        List<FieldRecord> fieldRecords = new ArrayList<>();

        for (FieldDto field : fields) {
            FieldRecord fieldRecord = new FieldRecord(field);
            fieldRecords.add(fieldRecord);
        }
        populateDefaultFields(fieldRecords);

        return new EntityRecord(null, entityId, fieldRecords);
    }

    @Override
    public EntityRecord getEntityInstance(Long entityId, Long instanceId) {
        EntityDto entity = getEntity(entityId);
        validateCredentials(entity);
        MotechDataService service = getServiceForEntity(entity);

        Object instance = service.retrieve(ID, instanceId);
        if (instance == null) {
            throw new ObjectNotFoundException();
        }

        List<FieldDto> fields = entityService.getEntityFields(entityId);

        return instanceToRecord(instance, entity, fields, service);
    }

    @Override
    public FieldRecord getInstanceValueAsRelatedField(Long entityId, Long fieldId, Long instanceId) {
        validateCredentials(getEntity(entityId));
        try {
            FieldRecord fieldRecord;
            FieldDto field = entityService.getEntityFieldById(entityId, fieldId);
            MotechDataService service = DataServiceHelper.getDataService(bundleContext, field.getMetadata(RELATED_CLASS).getValue());

            Object instance = service.findById(instanceId);
            if (instance == null) {
                throw new ObjectNotFoundException();
            }
            fieldRecord = new FieldRecord(field);
            fieldRecord.setValue(parseValueForDisplay(instance, field.getMetadata(Constants.MetadataKeys.RELATED_FIELD)));
            fieldRecord.setDisplayValue(instance.toString());
            return fieldRecord;
        } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new ObjectReadException(e);
        }
    }

    @Override
    public void deleteInstance(Long entityId, Long instanceId) {
        EntityDto entity = getEntity(entityId);
        validateCredentials(entity);
        MotechDataService service = getServiceForEntity(entity);

        service.delete(ID, instanceId);
    }

    @Override
    public void revertInstanceFromTrash(Long entityId, Long instanceId) {
        EntityDto entity = getEntity(entityId);
        validateCredentials(entity);
        MotechDataService service = getServiceForEntity(entity);

        Object trash = service.findTrashInstanceById(instanceId, entityId);
        List<FieldRecord> fieldRecords = new LinkedList<>();
        Class<?> entityClass;
        Object newInstance = null;
        try {
            for (FieldDto field : entityService.getEntityFields(entity.getId())) {
                if ("id".equalsIgnoreCase(field.getBasic().getDisplayName())) {
                    continue;
                }
                Field f = trash.getClass().getDeclaredField(field.getBasic().getName());
                f.setAccessible(true);
                FieldRecord record = new FieldRecord(field);
                record.setValue(f.get(trash));
                fieldRecords.add(record);
            }
            entityClass = getEntityClass(entity);
            newInstance = entityClass.newInstance();
            updateFields(newInstance, fieldRecords, service, null);
        } catch (Exception e) {
            LOGGER.error("Field for " + entity.getClassName() + " not found", e);
        }

        service.revertFromTrash(newInstance, trash);
    }

    @Override
    public void verifyEntityAccess(Long entityId) {
        EntityDto entity = getEntity(entityId);
        validateCredentials(entity);
    }

    private void populateDefaultFields(List<FieldRecord> fieldRecords) {
        for (FieldRecord record : fieldRecords) {
            if (Constants.Util.CREATOR_FIELD_NAME.equals(record.getName()) ||
                    Constants.Util.OWNER_FIELD_NAME.equals(record.getName())) {
                record.setValue(SecurityContextHolder.getContext().getAuthentication().getName());
            }
        }
    }

    private LookupDto getLookupByName(Long entityId, String lookupName) {
        LookupDto lookup = entityService.getLookupByName(entityId, lookupName);
        if (lookup == null) {
            throw new LookupNotFoundException();
        }
        return lookup;
    }

    private EntityDto getEntity(Long entityId) {
        EntityDto entityDto = entityService.getEntity(entityId);
        assertEntityExists(entityDto);
        return entityDto;
    }

    private MotechDataService getServiceForEntity(EntityDto entity) {
        String className = entity.getClassName();
        return DataServiceHelper.getDataService(bundleContext, className);
    }

    private void updateFields(Object instance, List<FieldRecord> fieldRecords, MotechDataService service, Long deleteValueFieldId) {
        updateFields(instance, fieldRecords, service, deleteValueFieldId, false);
    }

    private void updateFields(Object instance, List<FieldRecord> fieldRecords, MotechDataService service, Long deleteValueFieldId, boolean retainId) {
        try {
            for (FieldRecord fieldRecord : fieldRecords) {
                if (!(retainId && ID.equals(fieldRecord.getName())) && !fieldRecord.getType().isRelationship()) {
                    setProperty(instance, fieldRecord, service, deleteValueFieldId);
                } else if (fieldRecord.getType().isRelationship()) {
                    setRelationProperty(instance, fieldRecord);
                }
            }
        } catch (Exception e) {
            throw new ObjectUpdateException(e);
        }
    }

    private List<EntityRecord> instancesToRecords(Collection instances, EntityDto entity, List<FieldDto> fields,
                                                  MotechDataService service) {
        List<EntityRecord> records = new ArrayList<>();
        for (Object instance : instances) {
            EntityRecord record = instanceToRecord(instance, entity, fields, service);
            records.add(record);
        }
        return records;
    }

    private EntityRecord instanceToRecord(Object instance, EntityDto entityDto, List<FieldDto> fields,
                                          MotechDataService service) {
        if (instance == null) {
            return null;
        }

        try {
            List<FieldRecord> fieldRecords = new ArrayList<>();

            for (FieldDto field : fields) {
                Object value = getProperty(instance, field, service);
                Object displayValueForRelatedInstances = null;

                if (field.getType().isRelationship()) {
                    if (field.getType().equals(TypeDto.ONE_TO_MANY_RELATIONSHIP) || field.getType().equals(TypeDto.MANY_TO_MANY_RELATIONSHIP)) {
                        displayValueForRelatedInstances = buildDisplayValuesMap((Collection) value);
                    } else {
                        if (value != null) {
                            String toStringResult = value.toString();
                            displayValueForRelatedInstances = toStringResult.length() > TO_STRING_MAX_LENGTH ?
                                    toStringResult.substring(0, TO_STRING_MAX_LENGTH + 1) + "..." : toStringResult;
                        }
                    }
                }

                value = parseValueForDisplay(value, field.getMetadata(Constants.MetadataKeys.RELATED_FIELD));

                FieldRecord fieldRecord = new FieldRecord(field);
                fieldRecord.setValue(value);
                fieldRecord.setDisplayValue(displayValueForRelatedInstances);
                fieldRecords.add(fieldRecord);
            }

            Number id = (Number) PropertyUtil.safeGetProperty(instance, ID);
            return new EntityRecord(id == null ? null : id.longValue(), entityDto.getId(), fieldRecords);
        } catch (Exception e) {
            throw new ObjectReadException(e);
        }
    }

    private Map<Long, String> buildDisplayValuesMap(Collection values) throws InvocationTargetException, IllegalAccessException {
        Map<Long, String> displayValues = new HashMap<>();
        for (Object obj : values) {
            Method method = MethodUtils.getAccessibleMethod(obj.getClass(), "getId", (Class[]) null);
            Long key = (Long) method.invoke(obj);
            String toStringResult = obj.toString();
            displayValues.put(key, toStringResult.length() > TO_STRING_MAX_LENGTH ?
                        toStringResult.substring(0 , TO_STRING_MAX_LENGTH + 1) + "..." : toStringResult);
        }
        return displayValues;
    }

    private HistoryRecord convertToHistoryRecord(Object object, EntityDto entity, Long instanceId,
                                                 MotechDataService service) {
        Long entityId = entity.getId();

        EntityRecord entityRecord = instanceToRecord(object, entity, entityService.getEntityFields(entityId), service);
        Long historyInstanceSchemaVersion = (Long) PropertyUtil.safeGetProperty(object,
                HistoryTrashClassHelper.schemaVersion(object.getClass()));
        Long currentSchemaVersion = entityService.getCurrentSchemaVersion(entity.getClassName());

        return new HistoryRecord(entityRecord.getId(), instanceId,
                historyInstanceSchemaVersion.equals(currentSchemaVersion), entityRecord.getFields());
    }

    private void assertEntityExists(EntityDto entity) {
        if (entity == null) {
            throw new EntityNotFoundException();
        }
    }

    private void setProperty(Object instance, FieldRecord fieldRecord, MotechDataService service, Long deleteValueFieldId) throws NoSuchMethodException, ClassNotFoundException {
        String fieldName = fieldRecord.getName();
        TypeDto type = getType(fieldRecord);

        String methodName = "set" + StringUtils.capitalize(fieldName);
        ComboboxHolder holder = type.isCombobox() ? new ComboboxHolder(instance, fieldRecord) : null;
        String methodParameterType = getMethodParameterType(type, holder);

        ClassLoader classLoader = instance.getClass().getClassLoader();

        Class<?> parameterType;
        Object parsedValue;
        if (Byte[].class.getName().equals(methodParameterType) || byte[].class.getName().equals(methodParameterType)) {
            parameterType = getCorrectByteArrayType(methodParameterType);

            parsedValue = parseBlobValue(fieldRecord, service, fieldName, deleteValueFieldId, instance);
        } else {
            parameterType = classLoader.loadClass(methodParameterType);

            parsedValue = parseValue(holder, methodParameterType, fieldRecord, classLoader);
        }

        Method method = MethodUtils.getAccessibleMethod(instance.getClass(), methodName, parameterType);

        if (method == null && TypeHelper.hasPrimitive(parameterType)) {
            method = MethodUtils.getAccessibleMethod(instance.getClass(), methodName, TypeHelper.getPrimitive(parameterType));
            // if the setter is for a primitive, but we have a null, we leave the default
            if (method != null && parsedValue == null) {
                return;
            }
        }

        invokeMethod(method, instance, parsedValue, methodName, fieldName);
    }

    private void setRelationProperty(Object instance, FieldRecord fieldRecord) throws NoSuchMethodException, ClassNotFoundException, NoSuchFieldException, IllegalAccessException, InstantiationException, CannotCompileException {
        String fieldName = fieldRecord.getName();
        String methodName =  MemberUtil.getSetterName(fieldName);
        Class<?> clazz = instance.getClass().getClassLoader().loadClass(instance.getClass().getName());
        Field field = FieldUtils.getField(clazz, fieldName, true);
        Class<?> parameterType = field.getType();
        Object value = null;
        MotechDataService serviceForRelatedClass = null;
        TypeDto type = getType(fieldRecord);

        if (StringUtils.isNotEmpty(ObjectUtils.toString(fieldRecord.getValue()))) {
            if (type.equals(TypeDto.ONE_TO_MANY_RELATIONSHIP) || type.equals(TypeDto.MANY_TO_MANY_RELATIONSHIP)) {
                Class<?> genericType = (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
                serviceForRelatedClass = DataServiceHelper.getDataService(bundleContext, genericType.getName());
            } else if (type.equals(TypeDto.MANY_TO_ONE_RELATIONSHIP) || type.equals(TypeDto.ONE_TO_ONE_RELATIONSHIP)) {
                serviceForRelatedClass = DataServiceHelper.getDataService(bundleContext, parameterType.getName());
            }

            value = buildRelatedInstances(serviceForRelatedClass, parameterType, fieldRecord.getValue());
        }

        Method method = MethodUtils.getAccessibleMethod(instance.getClass(), methodName, parameterType);
        invokeMethod(method, instance, value, methodName, fieldName);
    }

    private Object buildRelatedInstances(MotechDataService service, Class<?> parameterType, Object fieldValue) throws IllegalAccessException, InstantiationException {
        Object parsedValue = null;

       if (Collection.class.isAssignableFrom(parameterType)) {
           if (Set.class.isAssignableFrom(parameterType)) {
               parsedValue = new HashSet();
           } else if (List.class.isAssignableFrom(parameterType)) {
               parsedValue = new ArrayList();
           } else {
               parsedValue = new ArrayList();
           }

           for (Object object : (Collection) fieldValue) {
               if (isFromUI(object)) {
                   ((Collection) parsedValue).add(findRelatedObjectById(((Map) object).get(ID), service));
               } else if (isHistoricalObject(object)){
                   String currentVersion = HistoryTrashClassHelper.currentVersion(object.getClass());
                   ((Collection) parsedValue).add(findRelatedObjectById(PropertyUtil.safeGetProperty(object, currentVersion), service));
               }
           }
       } else {
           if (isFromUI(fieldValue)) {
               parsedValue = findRelatedObjectById(((Map) fieldValue).get(ID), service);
           } else if (isHistoricalObject(fieldValue)){
               String currentVersion = HistoryTrashClassHelper.currentVersion(fieldValue.getClass());
               parsedValue = findRelatedObjectById(PropertyUtil.safeGetProperty(fieldValue, currentVersion), service);
           }
       }

       return parsedValue;
    }

    /**
     * Checks if the value is from the UI. When we updating or creating object with relationship field via the UI
     * the single related object is representing by Map.
     * @param value the value to be checked
     * @return true if the value is from the UI; false otherwise
     */
    private boolean isFromUI(Object value) {
        return value instanceof Map;
    }

    private boolean isHistoricalObject(Object object) {
        return ClassName.isHistoryClassName(object.getClass().getName());
    }

    private Object findRelatedObjectById(Object id, MotechDataService service) {
        //We need parse id value to the long type
        return service.findById(TypeHelper.parseNumber(id, Long.class.getName()).longValue());
    }

    private Class getCorrectByteArrayType(String type) {
        return Byte[].class.getName().equals(type) ? Byte[].class : byte[].class;
    }

    private TypeDto getType(FieldRecord fieldRecord) {
        TypeDto type = fieldRecord.getType();

        if (type.isTextArea()) {
            type = typeService.findType(String.class);
        }

        return type;
    }

    private Object parseBlobValue(FieldRecord fieldRecord, MotechDataService service, String fieldName,
                              Long deleteValueFieldId, Object instance) {
        Object parsedValue;
        if ((ArrayUtils.EMPTY_BYTE_OBJECT_ARRAY.equals(fieldRecord.getValue()) || ArrayUtils.EMPTY_BYTE_ARRAY.equals(fieldRecord.getValue()))
                && !fieldRecord.getId().equals(deleteValueFieldId)) {
            parsedValue = service.getDetachedField(instance, fieldName);
        } else {
            parsedValue = fieldRecord.getValue();
        }

        return verifyParsedValue(parsedValue);
    }

    private Object verifyParsedValue(Object parsedValue) {
        if (parsedValue == null) {
            return ArrayUtils.EMPTY_BYTE_OBJECT_ARRAY;
        }
        return parsedValue;
    }

    private void invokeMethod(Method method, Object instance, Object parsedValue, String methodName, String fieldName) throws NoSuchMethodException {
        if (method == null) {
            throw new NoSuchMethodException(String.format("No setter %s for field %s", methodName, fieldName));
        }

        try {
            if (method.getParameterTypes()[0].equals(byte[].class)) {
                method.invoke(instance, parsedValue instanceof byte[] ? parsedValue : ArrayUtils.toPrimitive((Byte[]) parsedValue));
            } else {
                method.invoke(instance, parsedValue);
            }
        } catch (Exception e) {
            throw new IllegalStateException(String.format("There was a problem with set value '%s' to field '%s'", parsedValue, fieldName), e);
        }
    }

    private Object parseValue(ComboboxHolder holder, String methodParameterType, FieldRecord fieldRecord, ClassLoader classLoader) {
        Object parsedValue = fieldRecord.getValue();
        String valueAsString = null == parsedValue ? null : TypeHelper.format(parsedValue);

        if (parsedValue instanceof Map) {
            if (fieldRecord.getMetadata(MAP_KEY_TYPE) != null && fieldRecord.getMetadata(MAP_VALUE_TYPE) != null) {
                Map<Object, Object> parsedValueAsMap = (Map<Object, Object>) parsedValue;
                Map<Object, Object> parsedMap = new LinkedHashMap<>();

                for (Iterator<Map.Entry<Object, Object>> it = parsedValueAsMap.entrySet().iterator(); it.hasNext();) {
                    Map.Entry<Object, Object> entry = it.next();
                    parsedMap.put(TypeHelper.parseMapValue(entry.getKey(), fieldRecord.getMetadata(MAP_KEY_TYPE).getValue(), true),
                        TypeHelper.parseMapValue(entry.getValue(), fieldRecord.getMetadata(MAP_VALUE_TYPE).getValue(), false));
                    it.remove();
                }

                parsedValueAsMap.putAll(parsedMap);
            }
        } else if (null != holder && holder.isEnumCollection()) {
            String genericType = holder.getEnumName();
            parsedValue = TypeHelper.parse(valueAsString, holder.getTypeClassName(), genericType, classLoader);
        } else {
            parsedValue = TypeHelper.parse(valueAsString, methodParameterType, classLoader);
        }

        return parsedValue;
    }

    private String getMethodParameterType(TypeDto type, ComboboxHolder holder) {
        String methodParameterType;

        if (type.isCombobox() && null != holder) {
            methodParameterType = holder.getTypeClassName();
        } else {
            methodParameterType = type.getTypeClass();
        }

        return methodParameterType;
    }

    private Object getProperty(Object instance, FieldDto field, MotechDataService service)
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        String fieldName = field.getBasic().getName();

        PropertyDescriptor propertyDescriptor = PropertyUtil.getPropertyDescriptor(instance, fieldName);
        Method readMethod = propertyDescriptor.getReadMethod();

        if (readMethod == null) {
            throw new NoSuchMethodException(String.format("No getter for field %s", fieldName));
        }

        if (TypeDto.BLOB.getTypeClass().equals(field.getType().getTypeClass())) {
            return ArrayUtils.EMPTY_BYTE_OBJECT_ARRAY;
        }

        try {
            return readMethod.invoke(instance);
        } catch (InvocationTargetException e) {
            LOGGER.debug("Invocation target exception thrown when retrieving field {}. This may indicate a non loaded field",
                    fieldName, e);
            // fallback to the service
            return service.getDetachedField(instance, fieldName);
        }
    }

    private Object parseValueForDisplay(Object value, MetadataDto relatedFieldMetadata) throws IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        Object parsedValue = value;

        if (parsedValue instanceof DateTime) {
            parsedValue = DTF.print((DateTime) parsedValue);
        } else if (parsedValue instanceof Date) {
            parsedValue = DTF.print(((Date) parsedValue).getTime());
        } else if (parsedValue instanceof Time) {
            parsedValue = ((Time) parsedValue).timeStr();
        } else if (parsedValue instanceof LocalDate) {
            parsedValue = parsedValue.toString();
        } else if (relatedFieldMetadata != null) {
            parsedValue = removeCircularRelations(parsedValue, relatedFieldMetadata.getValue());
        }

        return parsedValue;
    }

    private Object removeCircularRelations(Object object, String relatedField) {
        // we must also handle a field that is a collection
        // because of this we handle regular fields as single objects collection here
        Collection objectsCollection = (object instanceof Collection) ? (Collection) object : Arrays.asList(object);

        for (Object item : objectsCollection) {
            if (item != null) {
                PropertyUtil.safeSetProperty(item, relatedField, null);
            }
        }

        return object;
    }

    private Class<?> getEntityClass(EntityDto entity) throws ClassNotFoundException {
        // get the declaring bundle, for DDE the module bundle, for EUDE the generated entities bundle
        Bundle declaringBundle;
        if (entity.isDDE()) {
            declaringBundle = WebBundleUtil.findBundleByName(bundleContext, entity.getModule());
        } else {
            declaringBundle = WebBundleUtil.findBundleBySymbolicName(bundleContext,
                    Constants.BundleNames.MDS_ENTITIES_SYMBOLIC_NAME);
        }

        Class<?> clazz;

        // if no bundle found, fallback to the MDSClassLoader
        if (declaringBundle == null) {
            clazz = MDSClassLoader.getInstance().loadClass(entity.getClassName());
        } else {
            clazz = declaringBundle.loadClass(entity.getClassName());
        }

        return clazz;
    }

    private void validateCredentials(EntityDto entity) {
        boolean authorized = false;
        String username = getUsername();

        SecurityMode securityMode = entity.getSecurityMode();

        if (securityMode != null) {
            Set<String> securityMembers = entity.getSecurityMembers();

            if (securityMode == SecurityMode.EVERYONE) {
                authorized = true;
            } else if (securityMode == SecurityMode.USERS) {
                if (securityMembers.contains(username)) {
                    authorized = true;
                }
            } else if (securityMode == SecurityMode.PERMISSIONS) {
                for (String permission : getUserPermissions()) {
                    if (securityMembers.contains(permission)) {
                        authorized = true;
                    }
                }
            }

            if (!authorized && !securityMode.isInstanceRestriction()) {
                throw new SecurityException();
            }
        }
    }

    @Autowired
    public void setEntityService(EntityService entityService) {
        this.entityService = entityService;
    }

    @Autowired
    public void setTrashService(TrashService trashService) {
        this.trashService = trashService;
    }

    @Autowired
    public void setBundleContext(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    @Autowired
    public void setHistoryService(HistoryService historyService) {
        this.historyService = historyService;
    }

    @Autowired
    public void setTypeService(TypeService typeService) {
        this.typeService = typeService;
    }
}
