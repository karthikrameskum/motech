package org.motechproject.tasks.service;

import org.apache.commons.lang.WordUtils;
import org.motechproject.commons.api.MotechException;
import org.motechproject.tasks.domain.Task;
import org.motechproject.tasks.events.constants.TaskFailureCause;
import org.motechproject.tasks.ex.TaskHandlerException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * TaskContext holds task trigger event and data provider lookup objects that are used while executing filters/actions.
 */
public class TaskContext {

    private Task task;
    private Map<String, Object> parameters;
    private TaskActivityService activityService;
    private Set<DataSourceObject> dataSourceObjects;

    /**
     * Class constructor.
     *
     * @param task  the task, not null
     * @param parameters  the task parameters
     * @param activityService  the activity service, not null
     */
    public TaskContext(Task task, Map<String, Object> parameters, TaskActivityService activityService) {
        this.task = task;
        this.parameters = parameters;
        this.activityService = activityService;
        this.dataSourceObjects = new HashSet<>();
    }

    /**
     * Adds the given data source to this task.
     *
     * @param objectId  the ID of the object, not null
     * @param dataSourceObject  the result of lookup execution, not null
     * @param failIfDataNotFound  defines whether task should fail if the data wasn't found
     */
    public void addDataSourceObject(String objectId, Object dataSourceObject, boolean failIfDataNotFound) {
        dataSourceObjects.add(new DataSourceObject(objectId, dataSourceObject, failIfDataNotFound));
    }

    /**
     * Returns the value of the trigger with the given key.
     *
     * @param key  the key of the trigger, not null
     * @return  the value of the trigger with the given key
     */
    public Object getTriggerValue(String key) {
        Object value = null;

        if (parameters != null) {
            value = getFieldValue(parameters, key);
        }

        return value;
    }

    /**
     * Returns the value of data source object based on it's field, id and type.
     *
     * @param objectId  the id of the object, not null
     * @param field  the name of the field, not null
     * @param objectType  the type of the object
     * @return  the value of data source object
     * @throws TaskHandlerException
     */
    public Object getDataSourceObjectValue(String objectId, String field, String objectType) throws TaskHandlerException {
        DataSourceObject dataSourceObject = getDataSourceObject(objectId);
        if (dataSourceObject == null) {
            throw new TaskHandlerException(TaskFailureCause.DATA_SOURCE, "task.error.objectOfTypeNotFound", objectType);
        }

        if (dataSourceObject.getObjectValue() == null) {
            if (dataSourceObject.isFailIfNotFound()) {
                throw new TaskHandlerException(TaskFailureCause.DATA_SOURCE, "task.error.objectOfTypeNotFound", objectType);
            }
            publishWarningActivity("task.warning.notFoundObjectForType", objectType);
            return null;
        }

        try {
            return getFieldValue(dataSourceObject.getObjectValue(), field);
        } catch (Exception e) {
            if (dataSourceObject.isFailIfNotFound()) {
                throw new TaskHandlerException(TaskFailureCause.DATA_SOURCE, "task.error.objectDoesNotContainField", e, field);
            }
            publishWarningActivity("task.warning.objectNotContainsField", field);
        }
        return null;
    }

    /**
     * Publishes warning activity for this task.
     *
     * @param message  the message to be published
     * @param field  the name of the field
     */
    public void publishWarningActivity(String message, String field) {
        activityService.addWarning(task, message, field);
    }

    public Task getTask() {
        return task;
    }

    public Map<String, Object> getTriggerParameters() {
        return parameters;
    }

    private DataSourceObject getDataSourceObject(String objectId) {
        for (DataSourceObject dataSourceObject : dataSourceObjects) {
            if (dataSourceObject.getObjectId().equals(objectId)) {
                return dataSourceObject;
            }
        }
        return null;
    }

    private Object getFieldValue(Object object, String field) {
        String[] subFields = field.split("\\.");
        Object current = object;

        for (String subField : subFields) {
            if (current == null) {
                throw new IllegalStateException("Field on path is null");
            } else if (current instanceof Map) {
                current = ((Map) current).get(subField);
            } else {
                try {
                    Method method = current.getClass().getMethod("get" + WordUtils.capitalize(subField));
                    current = method.invoke(current);
                } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                    throw new MotechException(e.getMessage(), e);
                }
            }
        }

        return current;
    }
}
