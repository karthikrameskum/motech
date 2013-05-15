(function () {

    'use strict';

    /* Controllers */

    var widgetModule = angular.module('motech-tasks');

    widgetModule.controller('DashboardCtrl', function ($scope, $filter, Tasks, Activities) {
        var RECENT_TASK_COUNT = 7, tasks, activities = [],
            searchMatch = function (item, method, searchQuery) {
                var result;

                if (!searchQuery) {
                    if (method === 'pausedTaskFilter') {
                        result = item.task.enabled === true;
                    } else if (method === 'activeTaskFilter') {
                        result = item.task.enabled === false;
                    } else if (method === 'noItems') {
                        result = false;
                    } else {
                        result = true;
                    }
                } else if (method === 'pausedTaskFilter' && item.task.description) {
                    result = item.task.enabled === true && (item.task.description.toLowerCase().indexOf(searchQuery.toLowerCase()) !== -1 || item.task.name.toLowerCase().indexOf(searchQuery.toLowerCase()) !== -1);
                } else if (method === 'activeTaskFilter' && item.task.description) {
                    result = item.task.enabled === false && (item.task.description.toLowerCase().indexOf(searchQuery.toLowerCase()) !== -1 || item.task.name.toLowerCase().indexOf(searchQuery.toLowerCase()) !== -1);
                } else if (method === 'activeTaskFilter' && item.task.description) {
                    result = item.task.description.toLowerCase().indexOf(searchQuery.toLowerCase()) !== -1 || item.task.name.toLowerCase().indexOf(searchQuery.toLowerCase()) !== -1;
                } else if (method === 'allItems' && item.task.description) {
                    result = item.task.description.toLowerCase().indexOf(searchQuery.toLowerCase()) !== -1 || item.task.name.toLowerCase().indexOf(searchQuery.toLowerCase()) !== -1;
                } else if (method === 'pausedTaskFilter') {
                    result = item.task.enabled === true && item.task.name.toLowerCase().indexOf(searchQuery.toLowerCase()) !== -1;
                } else if (method === 'activeTaskFilter') {
                    result = item.task.enabled === false && item.task.name.toLowerCase().indexOf(searchQuery.toLowerCase()) !== -1;
                } else if (method === 'noItems') {
                    result = false;
                } else {
                    result = item.task.name.toLowerCase().indexOf(searchQuery.toLowerCase()) !== -1;
                }

                return result;
            };

        $scope.resetItemsPagination();
        $scope.allTasks = [];
        $scope.activities = [];
        $scope.hideActive = false;
        $scope.hidePaused = false;
        $scope.filteredItems = [];
        $scope.itemsPerPage = 10;
        $scope.currentFilter = 'allItems';

        tasks = Tasks.query(function () {
            activities = Activities.query(function () {
                var item, i, j;

                for (i = 0; i < tasks.length; i += 1) {
                    item = {
                        task: tasks[i],
                        success: 0,
                        error: 0
                    };

                    for (j = 0; j < activities.length; j += 1) {
                        if (activities[j].task === item.task._id && activities[j].activityType === 'SUCCESS') {
                            item.success += 1;
                        }

                        if (activities[j].task === item.task._id && activities[j].activityType === 'ERROR') {
                            item.error += 1;
                        }
                    }
                    $scope.allTasks.push(item);
                }

                for (i = 0; i < RECENT_TASK_COUNT && i < activities.length; i += 1) {
                    for (j = 0; j < tasks.length; j += 1) {
                        if (activities[i].task === tasks[j]._id) {
                            $scope.activities.push({
                                task: activities[i].task,
                                trigger: tasks[j].trigger,
                                action: tasks[j].action,
                                date: activities[i].date,
                                type: activities[i].activityType,
                                name: tasks[j].name
                            });
                            break;
                        }
                    }
                }

                $scope.search();
            });
        });

        $scope.enableTask = function (item, enabled) {
            item.task.enabled = enabled;

            item.task.$save(dummyHandler, function (response) {
                item.task.enabled = !enabled;
                handleResponse('error.actionNotChangeTitle', 'error.actionNotChange', response);
            });
        };

        $scope.deleteTask = function (item) {
            jConfirm(jQuery.i18n.prop('task.confirm.remove'), jQuery.i18n.prop("header.confirm"), function (val) {
                if (val) {
                    item.task.$remove(function () {
                        $scope.allTasks.removeObject(item);
                        $scope.search();
                    }, alertHandler('task.error.removed', 'header.error'));
                }
            });
        };

        $scope.search = function () {
            $scope.filteredItems = $filter('filter')($scope.allTasks, function (item) {
                return item && searchMatch(item, $scope.currentFilter, $scope.query);
            });

            $scope.setCurrentPage(0);
            $scope.groupToPages($scope.filteredItems, $scope.itemsPerPage);
        };

        $scope.setHideActive = function () {
            if ($scope.hideActive === true) {
                $scope.hideActive = false;
                $scope.setFilter($scope.hidePaused ? 'pausedTaskFilter' : 'allItems');

                $('.setHideActive').find('i').removeClass("icon-ban-circle").addClass('icon-ok');
            } else {
                $scope.hideActive = true;
                $scope.setFilter($scope.hidePaused ? 'noItems' : 'activeTaskFilter');

                $('.setHideActive').find('i').removeClass("icon-ok").addClass('icon-ban-circle');
            }
        };

        $scope.setHidePaused = function () {
            if ($scope.hidePaused === true) {
                $scope.hidePaused = false;
                $scope.setFilter($scope.hideActive ? 'activeTaskFilter' : 'allItems');

                $('.setHidePaused').find('i').removeClass("icon-ban-circle").addClass('icon-ok');
            } else {
                $scope.hidePaused = true;
                $scope.setFilter($scope.hideActive ? 'noItems' : 'pausedTaskFilter');

                $('.setHidePaused').find('i').removeClass("icon-ok").addClass('icon-ban-circle');
            }
        };

        $scope.setFilter = function (method) {
            $scope.currentFilter = method;
            $scope.search();
        };

    });

    widgetModule.controller('ManageTaskCtrl', function ($scope, ManageTaskUtils, Channels, DataSources, Tasks, $q, $timeout, $routeParams, $http, $compile) {
        $scope.util = ManageTaskUtils;
        $scope.selectedDataSources = [];

        $q.all([$scope.util.doQuery($q, Channels), $scope.util.doQuery($q, DataSources)]).then(function(data) {
            blockUI();

            $scope.channels = data[0];
            $scope.dataSources = data[1];

            if ($routeParams.taskId === undefined) {
                $scope.task = {};
            } else {
                $scope.task = Tasks.get({ taskId: $routeParams.taskId }, function () {
                    var triggerChannel, trigger, action, actionBy = [],
                        dataSource, dataSourceId, object, obj, i;

                    if ($scope.task.trigger) {
                        triggerChannel = $scope.util.find({
                            where: $scope.channels,
                            by: {
                                what: 'moduleName',
                                equalTo: $scope.task.trigger.moduleName
                            }
                        });

                        trigger = triggerChannel && $scope.util.find({
                            where: triggerChannel.triggerTaskEvents,
                            by: {
                                what: 'subject',
                                equalTo: $scope.task.trigger.subject
                            }
                        });

                        if (trigger) {
                            $scope.util.trigger.select($scope, triggerChannel, trigger);
                        }
                    }

                    if ($scope.task.filters) {
                        $http.get($scope.util.FILTER_SET_PATH).success(function (html) {
                            angular.element($scope.util.BUILD_AREA_ID).append($compile(html)($scope));
                            angular.element($scope.util.BUILD_AREA_ID + ' #filter-set #collapse-filter').collapse();
                        });
                    }

                    for (dataSourceId in $scope.task.additionalData) {
                        if ($scope.task.additionalData.hasOwnProperty(dataSourceId)) {
                            dataSource = $scope.util.find({
                                where: $scope.dataSources,
                                by: {
                                    what: '_id',
                                    equalTo: dataSourceId
                                }
                            });

                            for (i = 0; i < $scope.task.additionalData[dataSourceId].length; i += 1) {
                                object = $scope.task.additionalData[dataSourceId][i];

                                obj = $scope.util.find({
                                    where: dataSource.objects,
                                    by: {
                                        what: 'type',
                                        equalTo: object.type
                                    }
                                });

                                $scope.selectedDataSources.push({
                                    id: object.id,
                                    dataSourceId: dataSource._id,
                                    dataSourceName: dataSource.name,
                                    displayName: obj.displayName,
                                    type: object.type,
                                    failIfDataNotFound: object.failIfDataNotFound,
                                    lookup: {
                                        field: object.lookupField,
                                        value: object.lookupValue
                                    }
                                });
                            }
                        }
                    }

                    $scope.selectedDataSources.sort(function (one, two) {
                        return one.id - two.id;
                    });

                    angular.forEach($scope.selectedDataSources, function (data) {
                        var childScope = $scope.$new();

                        data.lookup.value = $scope.util.convertToView($scope, 'UNICODE', data.lookup.value);
                        childScope.data = data;

                        $http.get($scope.util.DATA_SOURCE_PATH).success(function (html) {
                            angular.element($scope.util.BUILD_AREA_ID).append($compile(html)(childScope));
                            angular.element($scope.util.BUILD_AREA_ID + " .accordion-body").collapse();
                        });
                    });

                    if ($scope.task.action) {
                        $scope.selectedActionChannel = $scope.util.find({
                            where: $scope.channels,
                            by: {
                                what: 'moduleName',
                                equalTo: $scope.task.action.moduleName
                            }
                        });

                        if ($scope.selectedActionChannel) {
                            if ($scope.task.action.subject) {
                                actionBy.push({ what: 'subject', equalTo: $scope.task.action.subject });
                            }

                            if ($scope.task.action.serviceInterface && $scope.task.action.serviceMethod) {
                                actionBy.push({ what: 'serviceInterface', equalTo: $scope.task.action.serviceInterface });
                                actionBy.push({ what: 'serviceMethod', equalTo: $scope.task.action.serviceMethod });
                            }

                            action = $scope.util.find({
                                where: $scope.selectedActionChannel.actionTaskEvents,
                                by: actionBy
                            });

                            if (action) {
                                $timeout(function () {
                                    $scope.util.action.select($scope, action);
                                    angular.element('#collapse-action').collapse();

                                    angular.forEach($scope.selectedAction.actionParameters, function (param) {
                                        param.value = $scope.task.actionInputFields[param.key];
                                        param.value = $scope.util.convertToView($scope, param.type, param.value);
                                    });
                                });
                            }
                        }
                    }
                });
            }

            unblockUI();
        });

        $scope.selectTrigger = function (channel, trigger) {
            if ($scope.task.trigger) {
                motechConfirm('task.confirm.trigger', "header.confirm", function (val) {
                    if (val) {
                        $scope.util.trigger.remove($scope);
                        $scope.util.trigger.select($scope, channel, trigger);
                    }
                });
            } else {
                $scope.util.trigger.select($scope, channel, trigger);
            }
        };

        $scope.removeTrigger = function ($event) {
            $event.stopPropagation();

            motechConfirm('task.confirm.trigger', "header.confirm", function (val) {
                if (val) {
                    $scope.util.trigger.remove($scope);
                }
            });
        };

        $scope.addAction = function () {
            $scope.task.action = {};
        };

        $scope.removeAction = function () {
            motechConfirm('task.confirm.action', "header.confirm", function (val) {
                if (val) {
                    delete $scope.task.action;

                    if (!$scope.$$phase) {
                        $scope.$apply($scope.task);
                    }
                }
            });
        };

        $scope.selectActionChannel = function (channel) {
            if ($scope.selectedActionChannel && $scope.selectedAction) {
                motechConfirm('task.confirm.action', "header.confirm", function (val) {
                    if (val) {
                        $scope.task.action = {};
                        $scope.selectedActionChannel = channel;
                        delete $scope.selectedAction;

                        if (!$scope.$$phase) {
                            $scope.$apply($scope.task);
                        }
                    }
                });
            } else {
                $scope.selectedActionChannel = channel;
            }
        };

        $scope.getActions = function () {
            return ($scope.selectedActionChannel && $scope.selectedActionChannel.actionTaskEvents) || [];
        };

        $scope.selectAction = function (action) {
            if ($scope.selectedAction) {
                motechConfirm('task.confirm.action', "header.confirm", function (val) {
                    if (val) {
                        $scope.util.action.select($scope, action);
                    }
                });
            } else {
                $scope.util.action.select($scope, action);
            }
        };

        $scope.addFilterSet = function () {
            $scope.task.filters = [];

            $http.get($scope.util.FILTER_SET_PATH).success(function (html) {
                angular.element($scope.util.BUILD_AREA_ID).append($compile(html)($scope));
            });
        };

        $scope.removeFilterSet = function () {
            motechConfirm('task.confirm.filterSet', "header.confirm", function (val) {
                if (val) {
                    delete $scope.task.filters;
                    angular.element($scope.util.BUILD_AREA_ID).children($scope.util.FILTER_SET_ID).remove();

                    if (!$scope.$$phase) {
                        $scope.$apply($scope.task);
                    }
                }
            });
        };

        $scope.addFilter = function () {
            $scope.task.filters.push({});
        };

        $scope.removeFilter = function (filter) {
            $scope.task.filters.removeObject(filter);
        };

        $scope.operators = function (param) {
            var array = ['exist'];

            if (param) {
                if ($scope.util.isText(param.type)) {
                    $.merge(array, ["equals", "contains", "startsWith", "endsWith"]);
                } else if ($scope.util.isNumber(param.type)) {
                    $.merge(array, ["gt", "lt", "equal"]);
                }
            }

            return array;
        };

        $scope.addDataSource = function () {
            var childScope = $scope.$new(),
                length = $scope.selectedDataSources.length,
                lastData = $scope.selectedDataSources[length - 1];

            childScope.data = { id: (lastData && lastData.id + 1) || 0 };

            $scope.selectedDataSources.push(childScope.data);

            $http.get($scope.util.DATA_SOURCE_PATH).success(function (html) {
                angular.element($scope.util.BUILD_AREA_ID).append($compile(html)(childScope));
            });
        };

        $scope.removeData = function (data) {
            motechConfirm('task.confirm.dataSource', "header.confirm", function (val) {
                if (val) {
                    $scope.selectedDataSources.removeObject(data);
                    angular.element($scope.util.BUILD_AREA_ID).children($scope.util.DATA_SOURCE_PREFIX_ID + data.id).remove();

                    if (!$scope.$$phase) {
                        $scope.$apply($scope.selectedDataSources);
                    }
                }
            });
        };

        $scope.findObject = function (dataSourceId, type, id) {
            var dataSource, by, found;

            dataSource = $scope.util.find({
                where: $scope.dataSources,
                by: {
                    what: '_id',
                    equalTo: dataSourceId
                }
            });

            if (dataSource) {
                by = [];

                if (type) {
                    by.push({ what: 'type', equalTo: type });
                }

                if (id) {
                    by.push({ what: 'id', equalTo: id});
                }

                found = $scope.util.find({
                    where: dataSource.objects,
                    by: by
                });
            }

            return found;
        };

        $scope.selectDataSource = function (data, selected) {
            if (data.dataSourceId) {
                motechConfirm('task.confirm.changeDataSource', 'header.confirm', function (val) {
                    if (val) {
                        $scope.util.dataSource.select($scope, data, selected);
                    }
                });
            } else {
                $scope.util.dataSource.select($scope, data, selected);
            }
        };

        $scope.selectObject = function (data, selected) {
            if (data.type) {
                motechConfirm('task.confirm.changeObject', 'header.confirm', function (val) {
                    if (val) {
                        $scope.util.dataSource.selectObject($scope, data, selected);
                    }
                });
            } else {
                $scope.util.dataSource.selectObject($scope, data, selected);
            }
        };

        $scope.selectLookup = function(data, lookup) {
            data.lookup = {};
            data.lookup.field = lookup;
        };

        $scope.refactorDivEditable = function (value) {
            var result = $('<div/>').append(value),
                isChrome = $scope.util.isChrome($scope),
                isIE = $scope.util.isIE($scope);

            result.find('em').remove();

            result.find('span[data-prefix]').replaceWith(function () {
                var span = $(this), prefix = span.data('prefix'),
                    manipulations = span.attr('manipulate') || '',
                    type = span.data('type'),
                    object = {}, key, source, array, val, i;

                switch (prefix) {
                case $scope.util.TRIGGER_PREFIX:
                    for (i = 0; i < $scope.selectedTrigger.eventParameters.length; i += 1) {
                        if ($scope.msg($scope.selectedTrigger.eventParameters[i].displayName) === $(this).text()) {
                            key = $scope.selectedTrigger.eventParameters[i].eventKey;
                            break;
                        }
                    }
                    break;
                case $scope.util.DATA_SOURCE_PREFIX:
                    source = span.data('source');
                    object.type = span.data('object-type');
                    object.id = span.data('object-id');
                    key = span.data('field');
                    break;
                default:
                    key = span.data('value').toString();
                }

                if (manipulations !== "") {
                    if ($scope.util.isText(type)) {
                        array = manipulations.split(" ");

                        for (i = 0; i < array.length; i += 1) {
                            key = key.concat("?" + array[i]);
                        }
                    } else if ($scope.util.isDate(type)) {
                        key = key.concat("?" + manipulations);
                    }
                }

                key = key.replace(/\?+(?=\?)/g, '');

                switch (prefix) {
                case $scope.util.TRIGGER_PREFIX:
                    val = '{{{0}.{1}}}'.format(prefix, key);
                    break;
                case $scope.util.DATA_SOURCE_PREFIX:
                    val = '{{{0}.{1}.{2}#{3}.{4}}}'.format(prefix, $scope.msg(source), object.type, object.id, key);
                    break;
                default:
                    val = key;
                }

                return val;
            });

            if (isChrome) {
                result.find("div").replaceWith(function () {
                    return "\n{0}".format(this.innerHTML);
                });
            }

            if (isIE) {
                result.find("p").replaceWith(function () {
                    return "{0}<br>".format(this.innerHTML);
                });

                result.find("br").last().remove();
            }

            if (isChrome || isIE) {
                if (result[0].childNodes[result[0].childNodes.length - 1] === '<br>') {
                    result[0].childNodes[result[0].childNodes.length - 1].remove();
                }

                result.find("br").replaceWith("\n");
            }

            return result.text();
        };

        $scope.createDraggableElement = function (value) {
            var regex = new RegExp("\\{\\{.*?\\}\\}", "g"), element;

            element = value.replace(regex, function (data) {
                var indexOf = data.indexOf('.'),
                    prefix = data.slice(2, indexOf),
                    dataArray = data.slice(indexOf + 1, -2).split("?"),
                    key = dataArray[0],
                    manipulations = dataArray.slice(1),
                    span, cuts, param, type, field, dataSource, dataSourceId, object, id;

                switch (prefix) {
                case $scope.util.TRIGGER_PREFIX:
                    param = $scope.util.find({
                        where: $scope.selectedTrigger.eventParameters,
                        by: {
                            what: 'eventKey',
                            equalTo: key
                        }
                    });

                    if (!param) {
                        param = {
                            type: 'UNKNOWN',
                            displayName: key
                        };
                    }

                    span = $scope.util.createDraggableSpan({
                        msg: $scope.msg,
                        param: param,
                        prefix: prefix,
                        manipulations: manipulations
                    });
                    break;
                case $scope.util.DATA_SOURCE_PREFIX:
                    cuts = key.split('.');

                    dataSourceId = cuts[0];
                    type = cuts[1].split('#');
                    id = type.last();

                    cuts.remove(0, 1);
                    type.removeObject(id);

                    field = cuts.join('.');
                    type = type.join('#');

                    dataSource = $scope.util.find({
                        where: $scope.selectedDataSources,
                        by: [{
                            what: 'dataSourceId',
                            equalTo: dataSourceId
                        }, {
                            what: 'type',
                            equalTo: type
                        }, {
                            what: 'id',
                            equalTo: +id
                        }]
                    });

                    object = dataSource && $scope.findObject(dataSource.dataSourceId, dataSource.type);

                    param = object && $scope.util.find({
                        where: object.fields,
                        by: {
                            what: 'fieldKey',
                            equalTo: field
                        }
                    });

                    if (!param) {
                        param = {
                            type: 'UNKNOWN',
                            displayName: field
                        };
                    }

                    span = $scope.util.createDraggableSpan({
                        msg: $scope.msg,
                        param: param,
                        prefix: prefix,
                        manipulations: manipulations,
                        dataSourceName: dataSource.dataSourceName,
                        object: {
                            id: id,
                            type: type,
                            field: field,
                            displayName: object.displayName
                        }
                    });
                    break;
                }

                return span || '';
            });

            return element.replace(/\n/g, "<br>");
        };

        $scope.save = function (enabled) {
            var action = $scope.selectedAction;

            $scope.task.actionInputFields = {};
            $scope.task.enabled = enabled;
            $scope.task.additionalData = {};

            angular.forEach($scope.selectedDataSources, function (data) {
                var exists = false, lookupValue = (data.lookup && data.lookup.value) || '',
                    additionalData, object, i;

                lookupValue = $scope.util.convertToServer($scope, lookupValue);

                if ($scope.task.additionalData[data.dataSourceId] === undefined) {
                    $scope.task.additionalData[data.dataSourceId] = [];
                }

                additionalData = $scope.task.additionalData[data.dataSourceId];

                for (i = 0; i < additionalData.length; i += 1) {
                    object = additionalData[i];

                    if (object.id === data.id) {
                        object.type = data.type;
                        object.lookupField = data.lookup.field;
                        object.lookupValue = lookupValue;
                        object.failIfDataNotFound = data.failIfDataNotFound;

                        exists = true;
                        break;
                    }
                }

                if (!exists) {
                    additionalData.push({
                        id: data.id,
                        type: data.type,
                        lookupField: (data.lookup && data.lookup.field),
                        lookupValue: lookupValue,
                        failIfDataNotFound: data.failIfDataNotFound
                    });
                }
            });

            if (action) {
                angular.forEach(action.actionParameters, function (param) {
                    $scope.task.actionInputFields[param.key] = $scope.util.convertToServer($scope, param.value);
                });
            }

            blockUI();

            if (!$routeParams.taskId) {
                $http.post('../tasks/api/task/save', $scope.task)
                    .success(function () {
                        var msg = enabled ? 'task.success.savedAndEnabled' : 'task.success.saved', loc, indexOf;

                        motechAlert(msg, 'header.saved', function () {
                            unblockUI();
                            loc = window.location.toString();
                            indexOf = loc.indexOf('#');

                            window.location = "{0}#/dashboard".format(loc.substring(0, indexOf));
                        });
                    })
                    .error(function (response) {
                        delete $scope.task.actionInputFields;
                        delete $scope.task.enabled;
                        delete $scope.task.additionalData;

                        unblockUI();
                        jAlert($scope.util.createErrorMessage($scope, response), 'header.error');
                    });
            } else {
                $scope.task.$save(function() {
                    var loc, indexOf;

                    motechAlert('task.success.saved', 'header.saved', function () {
                        unblockUI();
                        loc = window.location.toString();
                        indexOf = loc.indexOf('#');

                        window.location = "{0}#/dashboard".format(loc.substring(0, indexOf));
                    });
                }, function (response) {
                    delete $scope.task.actionInputFields;
                    delete $scope.task.enabled;
                    delete $scope.task.additionalData;

                    unblockUI();
                    jAlert($scope.util.createErrorMessage($scope, response.data), 'header.error');
                });
            }
        };

        $scope.actionCssClass = function (prop) {
            var value, expression = false;

            if ($scope.selectedTrigger !== undefined) {
                value = prop.value === undefined ? '' : prop.value;

                if ($scope.util.canHandleModernDragAndDrop($scope)) {
                    value = $scope.refactorDivEditable(value);
                }

                expression = value.length === 0 || value === "\n";
            }

            return expression;
        };

        $scope.getBooleanValue = function (value) {
            return (value === 'true' || value === 'false') ? null : value;
        };

        $scope.setBooleanValue = function (index, value) {
            $scope.selectedAction.actionParameters[index].value = $scope.util.createBooleanSpan($scope, value);
        };

        $scope.checkedBoolean = function (index, val) {
            var prop = $scope.selectedAction.actionParameters[index],
                value = $scope.refactorDivEditable(prop.value === undefined ? '' : prop.value);

            return value === val;
        };

        $scope.getTaskValidationError = function (error) {
            var array = [], i;

            for (i = 0; i < error.args.length; i += 1) {
                array.push($scope.msg(error.args[i]));
            }

            return $scope.msg(error.message, array);
        };
    });

    widgetModule.controller('OldManageTaskCtrl', function ($scope, Channels, Tasks, DataSources, $routeParams, $http) {
        $scope.currentPage = {
            channels: 0,
            dataSource: 0,
            dataSourceObject: 0
        };

        $scope.pageSize = {
            channels : 10,
            dataSource: 1,
            dataSourceObject: 1
        };

        $scope.negationOperators = [
            { key: 'info.filter.is', value: 'true' },
            { key: 'info.filter.isNot', value: 'false' }
        ];

        $scope.task = {};
        $scope.filters = [];
        $scope.selectedDataSources = [];
        $scope.availableDataSources = [];
        $scope.allDataSources = [];

        $scope.allDataSources = DataSources.query(function () {
            $.merge($scope.availableDataSources, $scope.allDataSources);
        });

        $scope.channels = Channels.query(function () {
            if ($routeParams.taskId !== undefined) {
                $scope.task = Tasks.get({ taskId: $routeParams.taskId }, function () {
                    var trigger = $scope.task.trigger,
                        action = $scope.task.action,
                        regex = new RegExp('\\{\\{ad\\.(.+?)(\\..*?)\\}\\}', "g"),
                        rgx = new RegExp('ad\\.(.+?)\\.(.+?)\\#(.+?)\\.(.+)', "g"),
                        accept = function (action, info) {
                            var subject = action.subject && (action.subject === info.subject),
                                serviceInterface = action.serviceInterface && (action.serviceInterface === info.serviceInterface),
                                serviceMethod = action.serviceMethod && (action.serviceMethod === info.serviceMethod);

                            return subject || (serviceInterface && serviceMethod);
                        },
                        replaced = [],
                        lookupDS,
                        lookupObj,
                        lookupField,
                        found,
                        dataSource,
                        dataSourceId,
                        ds,
                        object,
                        obj,
                        key,
                        value,
                        i,
                        j;

                    $scope.setTaskEvent('trigger', trigger.channelName, trigger.moduleName, trigger.moduleVersion);
                    $scope.setTaskEvent('action', action.channelName, action.moduleName, action.moduleVersion);

                    for (i = 0; i < $scope.draggedTrigger.triggers.length; i += 1) {
                        if ($scope.draggedTrigger.triggers[i].subject === trigger.subject) {
                            $scope.selectedTrigger = $scope.draggedTrigger.triggers[i];
                            $scope.draggedTrigger.display = $scope.selectedTrigger.displayName;
                            break;
                        }
                    }

                    for (i = 0; i < $scope.draggedAction.actions.length; i += 1) {
                        if (accept($scope.draggedAction.actions[i], action)) {
                            $scope.selectedAction = $scope.draggedAction.actions[i];
                            $scope.draggedAction.display = $scope.selectedAction.displayName;
                            break;
                        }
                    }

                    if (!$scope.selectedTrigger || !$scope.selectedAction) {
                        return;
                    }

                    for (dataSourceId in $scope.task.additionalData) {
                        ds = $scope.findDataSourceById($scope.allDataSources, dataSourceId);
                        dataSource = { '_id': ds._id, 'name': ds.name, 'objects': [], 'available': ds.objects};

                        for (i = 0; i < $scope.task.additionalData[dataSourceId].length; i += 1) {
                            object = $scope.task.additionalData[dataSourceId][i];
                            obj = $scope.findObject(ds, object.type);

                            if (!obj) {
                                obj = {
                                    displayName: object.type,
                                    fields: [],
                                    lookupFields: [object.lookupField]
                                };
                            }

                            dataSource.objects.push({
                                id: object.id,
                                displayName: obj.displayName,
                                type: object.type,
                                fields: obj.fields,
                                lookupFields: obj.lookupFields,
                                lookup: {
                                    displayName: object.lookupValue,
                                    by: object.lookupValue,
                                    field: object.lookupField
                                },
                                failIfDataNotFound : object.failIfDataNotFound
                            });
                        }

                        $scope.selectedDataSources.push(dataSource);
                        $scope.availableDataSources.removeObject($scope.findDataSourceById($scope.availableDataSources, dataSourceId));
                    }

                    angular.forEach($scope.selectedDataSources, function (dataSource) {
                        angular.forEach(dataSource.objects, function (object) {
                            if (object.lookup.displayName.indexOf('trigger') === 0) {
                                object.lookup.displayName = $scope.msg($scope.findTriggerEventParameter(object.lookup.displayName.substring(8)).displayName);
                            } else if (object.lookup.displayName.indexOf('ad') === 0) {
                                rgx.lastIndex = 0;

                                found = rgx.exec(object.lookup.displayName);
                                lookupDS = $scope.findDataSourceById($scope.selectedDataSources, found[1]);
                                lookupObj = $scope.findObject(lookupDS, found[2], +found[3]);
                                lookupField = $scope.findObjectField(lookupObj, found[4]);

                                object.lookup.displayName = "{0} {1}#{2} {3}".format($scope.msg(lookupDS.name), $scope.msg(lookupObj.displayName), lookupObj.id, $scope.msg(lookupField.displayName));
                            }
                        });
                    });

                    for (i = 0; i < $scope.selectedAction.actionParameters.length; i += 1) {
                        key = $scope.selectedAction.actionParameters[i].key;
                        value = $scope.task.actionInputFields[key];

                        if ($scope.BrowserDetect.browser !== 'Chrome' && $scope.BrowserDetect.browser !== 'Explorer') {
                            while ((found = regex.exec(value)) !== null) {
                                replaced.push({
                                    find: '{{ad.' + found[1] + found[2] + '}}',
                                    value: '{{ad.' + $scope.msg($scope.findDataSourceById($scope.selectedDataSources, found[1]).name) + found[2] + '}}'
                                });
                            }

                            for (j = 0; j < replaced.length; j += 1) {
                                value = value.replace(replaced[j].find, replaced[j].value);
                            }

                            $scope.selectedAction.actionParameters[i].value = value;
                        } else {
                            if ($scope.selectedAction.actionParameters[i].type === 'BOOLEAN') {
                                value = '<span contenteditable="false" class="badge badge-' + (value ? 'success' : 'important') + '" data-value="' + value + '" data-prefix="other">' + (value ? $scope.msg('yes') : $scope.msg('no')) + '</span>';
                            }

                            $scope.selectedAction.actionParameters[i].value = $scope.createDraggableElement(value);
                        }
                    }

                    $scope.filters = [];
                    for (i = 0; i < $scope.task.filters.length; i += 1) {
                        for (j = 0; j < $scope.selectedTrigger.eventParameters.length; j += 1) {
                            if ($scope.selectedTrigger.eventParameters[j].displayName === $scope.task.filters[i].eventParameter.displayName) {
                                $scope.task.filters[i].eventParameter = $scope.selectedTrigger.eventParameters[j];
                                break;
                            }
                        }

                        if ($scope.task.filters[i].negationOperator) {
                            $scope.task.filters[i].negationOperator = $scope.negationOperators[0];
                        } else {
                            $scope.task.filters[i].negationOperator = $scope.negationOperators[1];
                        }

                        $scope.filters.push($scope.task.filters[i]);
                    }
                });
            }
        });

        $scope.numberOfSelectedDataSources = function () {
            return Math.ceil($scope.selectedDataSources.length / $scope.pageSize.dataSource);
        };

        $scope.numberOfDataSourceObjects = function (dataSource) {
            return Math.ceil(dataSource.objects.length / $scope.pageSize.dataSourceObject);
        };

        $scope.setTaskEvent = function (taskEventType, channelName, moduleName, moduleVersion) {
            var channel, selected, i, j;

            for (i = 0; i < $scope.channels.length; i += 1) {
                channel = $scope.channels[i];

                if (channel.moduleName === moduleName) {
                    selected = {
                        display: channelName,
                        channel: channelName,
                        module: moduleName,
                        version: moduleVersion
                    };

                    if (taskEventType === 'trigger') {
                        $scope.draggedTrigger = selected;
                        $scope.draggedTrigger.triggers = channel.triggerTaskEvents;
                    } else if (taskEventType === 'action') {
                        for (j = 0; j < channel.actionTaskEvents.length; j += 1) {
                            delete channel.actionTaskEvents[j].value;
                        }

                        $scope.draggedAction = selected;
                        $scope.draggedAction.actions = channel.actionTaskEvents;
                    }

                    break;
                }
            }
        };

        $scope.selectTaskEvent = function (taskEventType, taskEvent) {
            var i;

            if (taskEventType === 'trigger') {
                $scope.draggedTrigger.display = taskEvent.displayName;
                $scope.task.trigger = {
                    displayName: taskEvent.displayName,
                    channelName: $scope.draggedTrigger.channel,
                    moduleName: $scope.draggedTrigger.module,
                    moduleVersion: $scope.draggedTrigger.version,
                    subject: taskEvent.subject
                };
                $scope.selectedTrigger = taskEvent;
            } else if (taskEventType === 'action') {
                $scope.draggedAction.display = taskEvent.displayName;
                $scope.task.action = {
                    displayName: taskEvent.displayName,
                    channelName: $scope.draggedAction.channel,
                    moduleName: $scope.draggedAction.module,
                    moduleVersion: $scope.draggedAction.version
                };

                if (taskEvent.subject !== undefined) {
                    $scope.task.action.subject = taskEvent.subject;
                }

                if (taskEvent.serviceInterface !== undefined && taskEvent.serviceMethod !== undefined) {
                    $scope.task.action.serviceInterface = taskEvent.serviceInterface;
                    $scope.task.action.serviceMethod = taskEvent.serviceMethod;
                }

                $scope.selectedAction = taskEvent;
            }

            delete $scope.task.actionInputFields;

            if ($scope.selectedAction !== undefined) {
                for (i = 0; i < $scope.selectedAction.actionParameters.length; i += 1) {
                    delete $scope.selectedAction.actionParameters[i].value;
                }
            }
        };

        $scope.getTooltipMsg = function (selected) {
            return selected !== undefined ? $scope.msg('help.doubleClickToEdit') : '';
        };

        $scope.save = function (enabled) {
            var action = $scope.selectedAction, regex = new RegExp('\\{\\{ad\\.(.+?)(\\..*?)\\}\\}', "g"), key, value, found, replaced = [], i, j;

            $scope.task.actionInputFields = {};
            $scope.task.enabled = enabled;

            $scope.task.filters = [];
            if ($scope.filters.length !== 0) {
                for (i = 0; i < $scope.filters.length; i += 1) {
                    value = $scope.filters[i];
                    value.negationOperator = $scope.filters[i].negationOperator.value;

                    $scope.task.filters.push(value);
                }
            }

            angular.forEach(action.actionParameters, function (actionParameters) {
                if ($scope.BrowserDetect.browser !== 'Chrome' && $scope.BrowserDetect.browser !== 'Explorer') {
                    var regex = new RegExp("\\{\\{ad\\..*?\\}\\}", "g"), spans = [], r;

                    while ((r = regex.exec(actionParameters.value)) !== null) {
                        $.merge(spans, r);
                    }

                    angular.forEach(spans, function (span) {
                        var cuts = {}, source, type, id, ds, exists = false, object, i;

                        cuts.first = span.indexOf('.');
                        cuts.second = span.indexOf('.', cuts.first + 1);
                        cuts.third = span.indexOf('.', cuts.second + 1);

                        source = span.substring(cuts.first + 1, cuts.second);
                        type = span.substring(cuts.second + 1, cuts.third);
                        id = +type.substring(type.lastIndexOf('#') + 1);
                        type = type.substring(0, type.lastIndexOf('#'));

                        ds = $scope.findDataSourceByName($scope.selectedDataSources, source);

                        if ($scope.task.additionalData === undefined) {
                            $scope.task.additionalData = {};
                        }

                        if ($scope.task.additionalData[ds._id] === undefined) {
                            $scope.task.additionalData[ds._id] = [];
                        }

                        for (i = 0; i < $scope.task.additionalData[ds._id].length; i += 1) {
                            if ($scope.task.additionalData[ds._id][i].id === id) {
                                exists = true;
                                object = $scope.findObject(ds, type, id);
                                if ($scope.task.additionalData[ds._id][i].failIfDataNotFound !== object.failIfDataNotFound) {
                                    $scope.task.additionalData[ds._id][i].failIfDataNotFound = object.failIfDataNotFound;
                                }
                                break;
                            }
                        }

                        if (!exists) {
                            object = $scope.findObject(ds, type, id);

                            $scope.task.additionalData[ds._id].push({
                                id: object.id,
                                type: object.type,
                                lookupField: object.lookup.field,
                                lookupValue: object.lookup.by,
                                failIfDataNotFound: object.failIfDataNotFound
                            });
                        }

                    });
                } else {
                    $('<div>' + actionParameters.value + "</div>").find('span[data-prefix="ad"]').each(function (index, value) {
                        var span = $(value), source = span.attr('data-source'),
                            objectType = span.data('object-type'), objectId = span.data('object-id'),
                            dataSource, exists = false, object, i;

                        dataSource = $scope.findDataSourceByName($scope.selectedDataSources, source);

                        if ($scope.task.additionalData === undefined) {
                            $scope.task.additionalData = {};
                        }

                        if ($scope.task.additionalData[dataSource._id] === undefined) {
                            $scope.task.additionalData[dataSource._id] = [];
                        }

                        for (i = 0; i < $scope.task.additionalData[dataSource._id].length; i += 1) {
                            if ($scope.task.additionalData[dataSource._id][i].id === objectId) {
                                exists = true;
                                object = $scope.findObject(dataSource, objectType, objectId);
                                if ($scope.task.additionalData[dataSource._id][i].failIfDataNotFound !== object.failIfDataNotFound) {
                                    $scope.task.additionalData[dataSource._id][i].failIfDataNotFound = object.failIfDataNotFound;
                                }
                                break;
                            }
                        }

                        if (!exists) {
                            object = $scope.findObject(dataSource, objectType, objectId);

                            $scope.task.additionalData[dataSource._id].push({
                                id: object.id,
                                type: object.type,
                                lookupField: object.lookup.field,
                                lookupValue: object.lookup.by,
                                failIfDataNotFound: object.failIfDataNotFound
                            });
                        }
                    });
                }
            });

            angular.forEach($scope.selectedDataSources, function (dataSource) {
                angular.forEach(dataSource.objects, function (object) {
                    var regex = new RegExp('ad\\.(.+?)\\.(.+?)\\#(.+?)\\.(.+)', "g"), exists = false, exec, ds, obj, i;

                    if (object.lookup.by.indexOf('ad') === 0) {
                        exec = regex.exec(object.lookup.by);
                        ds = $scope.findDataSourceById($scope.selectedDataSources, exec[1]);
                        obj = $scope.findObject(ds, exec[2], +exec[3]);

                        if ($scope.task.additionalData === undefined) {
                            $scope.task.additionalData = {};
                        }

                        if ($scope.task.additionalData[ds._id] === undefined) {
                            $scope.task.additionalData[ds._id] = [];
                        }

                        for (i = 0; i < $scope.task.additionalData[ds._id].length; i += 1) {
                            if ($scope.task.additionalData[ds._id][i].id === obj.id) {
                                exists = true;
                                break;
                            }
                        }

                        if (!exists) {
                            $scope.task.additionalData[ds._id].push({
                                id: obj.id,
                                type: obj.type,
                                lookupField: obj.lookup.field,
                                lookupValue: obj.lookup.by,
                                failIfDataNotFound : obj.failIfDataNotFound
                            });
                        }
                    }
                });
            });

            for (i = 0; i < action.actionParameters.length; i += 1) {
                key = action.actionParameters[i].key;

                if ($scope.BrowserDetect.browser !== 'Chrome' && $scope.BrowserDetect.browser !== 'Explorer') {
                    value = action.actionParameters[i].value || '';
                } else {
                    value = $scope.refactorDivEditable(action.actionParameters[i].value  || '');
                }

                while ((found = regex.exec(value)) !== null) {
                    replaced.push({
                        find: '{{ad.' + found[1] + found[2] + '}}',
                        value: '{{ad.' + $scope.findDataSourceByName($scope.selectedDataSources, found[1])._id + found[2] + '}}'
                    });
                }

                for (j = 0; j < replaced.length; j += 1) {
                    value = value.replace(replaced[j].find, replaced[j].value);
                }

                $scope.task.actionInputFields[key] = value;
            }

            blockUI();

            if ($routeParams.taskId === undefined) {
                $http.post('../tasks/api/task/save', $scope.task).
                    success(function () {
                        var msg = enabled ? 'task.success.savedAndEnabled' : 'task.success.saved', loc, indexOf;

                        unblockUI();

                        motechAlert(msg, 'header.saved', function () {
                            loc = window.location.toString();
                            indexOf = loc.indexOf('#');

                            window.location = loc.substring(0, indexOf) + "#/dashboard";
                        });
                    }).error(function (response) {
                        unblockUI();
                        var msg = $scope.msg('task.error.saved') + '\n', i;

                        for (i = 0; i < response.length; i += 1) {
                            msg += ' - ' + $scope.getTaskValidationError(response[i]) + '\n';
                        }

                        delete $scope.task.actionInputFields;
                        delete $scope.task.enabled;
                        delete $scope.task.additionalData;

                        jAlert(msg, jQuery.i18n.prop('header.error'));
                    });
            } else {
                $scope.task.$save(function () {
                    var loc, indexOf;

                    unblockUI();

                    motechAlert('task.success.saved', 'header.saved', function () {
                        loc = window.location.toString();
                        indexOf = loc.indexOf('#');

                        window.location = loc.substring(0, indexOf) + "#/dashboard";
                    });
                }, function (response) {
                    var msg = $scope.msg('task.error.saved') + '\n', i;
                    unblockUI();
                    for (i = 0; i < response.data.length; i += 1) {
                        msg += ' - ' + $scope.getTaskValidationError(response.data[i]) + '\n';
                    }

                    delete $scope.task.actionInputFields;
                    delete $scope.task.enabled;
                    delete $scope.task.additionalData;

                    jAlert(msg, jQuery.i18n.prop('header.error'));
                });
            }
        };

        $scope.refactorDivEditable = function (value) {
            var result = $('<div>' + value + '</div>');

            result.find('span[data-prefix]').replaceWith(function () {
                var eventKey = '', source = $(this).data('source'),
                    type = $(this).data('object-type'), prefix = $(this).data('prefix'), field = $(this).data('field'),
                    id = $(this).data('object-id'), val, i, manipulation, man;

                if (prefix === 'trigger') {
                    for (i = 0; i < $scope.selectedTrigger.eventParameters.length; i += 1) {
                        if ($scope.msg($scope.selectedTrigger.eventParameters[i].displayName) === $(this).text()) {
                            eventKey = $scope.selectedTrigger.eventParameters[i].eventKey;
                        }
                    }
                } else if (prefix === 'ad') {
                    eventKey = field;
                } else {
                    eventKey = $(this).data('value').toString();
                }

                manipulation = this.attributes.getNamedItem('manipulate') !== null ? this.attributes.getNamedItem('manipulate').value : '';

                if (manipulation && manipulation !== "") {
                    if (this.attributes.getNamedItem('data-type').value === 'UNICODE' || this.attributes.getNamedItem('data-type').value === 'TEXTAREA') {
                        man = manipulation.split(" ");

                        for (i = 0; i < man.length; i += 1) {
                            eventKey = eventKey + "?" + man[i];
                        }
                    } else if (this.attributes.getNamedItem('data-type').value === 'DATE') {
                        eventKey = eventKey + "?" + manipulation;
                    }
                }

                eventKey = eventKey.replace(/\?+(?=\?)/g, '');

                if (prefix === 'trigger') {
                    val = '{{' + prefix + '.' + eventKey + '}}';
                } else if (prefix === 'ad') {
                    val = '{{' + prefix + '.' + $scope.msg(source) + '.' + type + '#' + id + '.' + eventKey + '}}';
                } else {
                    val = eventKey;
                }

                return val;
            });

            result.find('em').remove();

            if ($scope.BrowserDetect.browser === 'Chrome' && $scope.BrowserDetect.browser !== 'Explorer') {
                result.find("div").replaceWith(function () {
                    return "\n" + this.innerHTML;
                });
            }

            if ($scope.BrowserDetect.browser === 'Explorer') {
                result.find("p").replaceWith(function () {
                    return this.innerHTML + "<br>";
                });
                result.find("br").last().remove();
            }

            if ($scope.BrowserDetect.browser === 'Chrome' || $scope.BrowserDetect.browser === 'Explorer') {
                if (result[0].childNodes[result[0].childNodes.length - 1] === '<br>') {
                    result[0].childNodes[result[0].childNodes.length - 1].remove();
                }

                result.find("br").replaceWith("\n");
            }

            return result.text();
        };

        $scope.createDraggableElement = function (value) {
            var regex = new RegExp("\\{\\{.*?\\}\\}", "g");

            return value.replace(regex, $scope.buildSpan).replace(/\n/g, "<br>");
        };

        $scope.buildSpan = function (eventParameterKey) {
            var key = eventParameterKey.slice(eventParameterKey.indexOf('.') + 1, -2).split("?"),
                prefix = eventParameterKey.slice(2, eventParameterKey.indexOf('.')),
                span = "",
                param,
                type,
                field,
                cuts,
                dataSource,
                dataSourceId,
                object,
                id,
                manipulation;

            eventParameterKey = key[0];
            key.remove(0);
            manipulation = key;

            if (prefix === 'trigger') {
                param = $scope.findTriggerEventParameter(eventParameterKey);

                if (!param) {
                    param = {
                        type: 'unknown',
                        displayName: eventParameterKey
                    };
                }

                span = '<span unselectable="on" ' + ((param.type !== 'INTEGER' || param.type !== 'DOUBLE') ? 'manipulationpopover' : '') + ' contenteditable="false" class="popoverEvent nonEditable triggerField ng-scope ng-binding pointer badge ' +
                       (param.type !== 'unknown' ? 'badge-info' : 'badge-important') + '" data-prefix="' + prefix + '" data-type="' + param.type + '" style="position: relative;" ' +
                       (manipulation.length === 0 ? "" : 'manipulate="' + manipulation.join(" ") + '"') + '>' + $scope.msg(param.displayName) + '</span>';
            } else if (prefix === 'ad') {
                cuts = eventParameterKey.split('.');

                dataSourceId = cuts[0];
                type = cuts[1].split('#');
                id = type.last();

                cuts.remove(0, 1);
                type.removeObject(id);

                field = cuts.join('.');
                type = type.join('#');

                dataSource = $scope.findDataSourceById($scope.selectedDataSources, dataSourceId);
                object = $scope.findObject(dataSource, type);
                param = $scope.findObjectField(object, field);

                if (!param) {
                    param = {
                        type: 'unknown',
                        displayName: field
                    };
                }

                span = '<span unselectable="on"' + ((param.type !== 'INTEGER' || param.type !== 'DOUBLE') ? 'manipulationpopover' : '') + ' contenteditable="false" class="popoverEvent nonEditable triggerField ng-scope ng-binding pointer badge ' +
                       (param.type !== 'unknown' ? 'badge-warning' : 'badge-important') + '" data-type="' + param.type + '" data-prefix="' + prefix + '" data-source="' + dataSource.name + '" data-object="' + param.displayName + '" data-object-type="' +
                       type + '" data-field="' + field + '" data-object-id="' + id + '" style="position: relative;" ' + (manipulation.length === 0 ? "" : 'manipulate="' + manipulation.join(" ") + '"') + '>' +
                       $scope.msg(dataSource.name) + '.' + $scope.msg(object.displayName) + '#' + id + '.' + $scope.msg(param.displayName) + '</span>';
            }

            return span;
        };

        $scope.operators = function (event) {
            var operator = ['exist'];

            if (event && (event.type === 'UNICODE' || event.type === 'TEXTAREA')) {
                operator.push("equals");
                operator.push("contains");
                operator.push("startsWith");
                operator.push("endsWith");
            } else if (event && (event.type === 'INTEGER' || event.type === 'LONG' || event.type === 'DOUBLE')) {
                operator.push("gt");
                operator.push("lt");
                operator.push("equal");
            }

            return operator;
        };

        $scope.addFilter = function () {
            $scope.filters.push({});
        };

        $scope.removeNode = function (filter) {
            $scope.filters.removeObject(filter);
        };

        $scope.validateForm = function () {
            var i, param;

            if ($scope.selectedAction !== undefined && $scope.selectedTrigger !== undefined) {
                for (i = 0; i < $scope.selectedAction.actionParameters.length; i += 1) {
                    if ($scope.BrowserDetect.browser !== 'Chrome' && $scope.BrowserDetect.browser !== 'Explorer') {
                        param = $scope.selectedAction.actionParameters[i].value;
                    } else {
                        param = $scope.refactorDivEditable($scope.selectedAction.actionParameters[i].value || '');
                    }
                    if (param === null || param === undefined || param === "\n" || !param.trim().length) {
                        return false;
                    }
                }
            }
            if ($scope.task.name === undefined) {
                return false;
            }

            return $scope.validateFilterForm();
        };

        $scope.validateFilterForm = function () {
            var isPass = true, i;

            for (i = 0; i < $scope.filters.length; i += 1) {
                if (!$scope.filters[i].eventParameter || !$scope.filters[i].negationOperator || !$scope.filters[i].operator) {
                    isPass = false;
                }

                if ($scope.filters[i].operator && $scope.filters[i].operator !== 'exist' && !$scope.filters[i].expression) {
                    isPass = false;
                }
            }

            return isPass;
        };

        $scope.cssClass = function (prop) {
            var msg = 'validation-area';

            if (!prop) {
                msg = msg.concat(' error');
            }

            return msg;
        };

        $scope.actionCssClass = function (prop) {
            var msg = "control-group", value;

            if ($scope.selectedTrigger !== undefined) {
                value = $scope.refactorDivEditable(prop.value === undefined ? '' : prop.value);

                if (value.length === 0 || value === "\n") {
                    msg = msg.concat(' error');
                }
            }

            return msg;
        };

        $scope.addDataSource = function () {
            $scope.selectedDataSources.push({
                '_id': $scope.availableDataSources[0]._id,
                'name': $scope.availableDataSources[0].name,
                'objects': [],
                'available': $scope.availableDataSources[0].objects
            });

            $scope.availableDataSources.remove(0);
            $scope.currentPage.dataSource = $scope.selectedDataSources.length - 1;
        };

        $scope.changeDataSource = function (dataSource, available) {
            var regex = new RegExp('\\{\\{ad\\.' + $scope.msg(dataSource.name) + '\\..*?\\}\\}', "g"),
                spans = 0,
                change = function (ds, a) {
                    $scope.availableDataSources.removeObject(a);
                    $scope.availableDataSources.push($scope.findDataSourceByName($scope.allDataSources, ds.name));

                    ds._id = a._id;
                    ds.name = a.name;
                    ds.objects = [];
                    ds.available = a.objects;
                };

            if ($scope.BrowserDetect.browser !== 'Chrome' && $scope.BrowserDetect.browser !== 'Explorer') {
                angular.forEach($scope.selectedAction.actionParameters, function (param) {
                    var count;

                    while ((count = regex.exec(param.value)) !== null) {
                        spans = spans + count.length;
                    }
                });
            } else {
                spans = $('.actionField span[data-source="' + dataSource.name + '"]').length;
            }

            if (spans > 0) {
                motechConfirm('task.confirm.changeDataSource', 'header.confirm', function (r) {
                    if (r) {
                        if ($scope.BrowserDetect.browser !== 'Chrome' && $scope.BrowserDetect.browser !== 'Explorer') {
                            angular.forEach($scope.selectedAction.actionParameters, function (param) {
                                if (param.value !== undefined) {
                                    param.value = param.value.replace(regex, '');
                                }
                            });
                        } else {
                            $('.actionField span[data-source="' + dataSource.name + '"]').remove();
                            $('.actionField').change();
                        }

                        change(dataSource, available);
                        $scope.$apply();
                    }
                });
            } else {
                change(dataSource, available);
            }
        };

        $scope.selectObject = function (dataSourceName, object, selected) {
            var regex = new RegExp('\\{\\{ad\\.' + $scope.msg(dataSourceName) + '\\.' + object.type + '\\#' + object.id + '.*?\\}\\}', "g"),
                spans = 0,
                change = function (obj, sel) {
                    obj.displayName = sel.displayName;
                    obj.type = sel.type;
                    obj.fields = sel.fields;
                    obj.lookupFields = sel.lookupFields;
                    obj.lookup.field = sel.lookupFields[0];
                    obj.lookup.by = 'trigger.' + $scope.selectedTrigger.eventParameters[0].eventKey;
                    obj.lookup.displayName = $scope.msg($scope.selectedTrigger.eventParameters[0].displayName);
                };

            if ($scope.BrowserDetect.browser !== 'Chrome' && $scope.BrowserDetect.browser !== 'Explorer') {
                angular.forEach($scope.selectedAction.actionParameters, function (param) {
                    var count;

                    while ((count = regex.exec(param.value)) !== null) {
                        spans = spans + count.length;
                    }
                });
            } else {
                spans = $('.actionField span[data-source="' + dataSourceName + '"][data-object-type="' + object.type + '"][data-object-id="' + object.id + '"]').length;
            }

            if (spans > 0) {
                motechConfirm('task.confirm.changeDataSource', 'header.confirm', function (r) {
                    if (r) {
                        if ($scope.BrowserDetect.browser !== 'Chrome' && $scope.BrowserDetect.browser !== 'Explorer') {
                            angular.forEach($scope.selectedAction.actionParameters, function (param) {
                                if (param.value !== undefined) {
                                    param.value = param.value.replace(regex, '');
                                }
                            });
                        } else {
                            $('.actionField span[data-source="' + dataSourceName + '"][data-object-type="' + object.type + '"][data-object-id="' + object.id + '"]').remove();
                            $('.actionField').change();
                        }

                        change(object, selected);
                        $scope.$apply();
                    }
                });
            } else {
                change(object, selected);
            }
        };

        $scope.selectLookup = function (prefix, lookup, dsName, obj) {
            var dataSource = $scope.findDataSourceByName($scope.selectedDataSources, $scope.availableLookupValue.dataSourceName),
                target = $scope.findObject(dataSource, $scope.availableLookupValue.objectType, $scope.availableLookupValue.objectId),
                ds;

            if (prefix === 'trigger') {
                target.lookup.displayName = $scope.msg(lookup.displayName);
                target.lookup.by = prefix + '.' + lookup.eventKey;
            } else if (prefix === 'ad') {
                ds = $scope.findDataSourceByName($scope.selectedDataSources, dsName);

                target.lookup.displayName = $scope.msg(ds.name) + ' ' + $scope.msg(obj.displayName) + '#' + obj.id + ' ' + $scope.msg(lookup.displayName);
                target.lookup.by = prefix + '.' + ds._id + '.' + obj.type + '#' + obj.id + '.' + lookup.fieldKey;
            }

            $scope.closeLookupValueModal();
        };

        $scope.addObject = function (dataSource) {
            var first = dataSource.available[0];

            dataSource.objects.push({
                id: dataSource.objects.length + 1,
                displayName: first.displayName,
                type: first.type,
                fields: first.fields,
                lookupFields: first.lookupFields,
                lookup: {
                    displayName: $scope.msg($scope.selectedTrigger.eventParameters[0].displayName),
                    by: 'trigger.' + $scope.selectedTrigger.eventParameters[0].eventKey,
                    field: first.lookupFields[0]
                }
            });

            $scope.currentPage.dataSourceObject = dataSource.objects.length - 1;

            $('#addObjectNotification').notify({
                message: { text: $scope.msg('notification.addObject', $scope.msg(dataSource.name)) },
                type: 'blackgloss'
            }).show();
        };

        $scope.findTriggerEventParameter = function (eventKey) {
            var i, found;

            for (i = 0; i < $scope.selectedTrigger.eventParameters.length; i += 1) {
                if ($scope.selectedTrigger.eventParameters[i].eventKey === eventKey) {
                    found = $scope.selectedTrigger.eventParameters[i];
                    break;
                }
            }

            return found;
        };

        $scope.findDataSourceByName = function (dataSources, name) {
            var found;

            angular.forEach(dataSources, function (ds) {
                if (ds.name === name || $scope.msg(ds.name) === name) {
                    found = ds;
                }
            });

            return found;
        };

        $scope.findDataSourceById = function (dataSources, dataSourceId) {
            var found;

            angular.forEach(dataSources, function (ds) {
                if (ds._id === dataSourceId) {
                    found = ds;
                }
            });

            return found;
        };

        $scope.findObject = function (dataSource, type, id) {
            var found;

            angular.forEach(dataSource.objects, function (obj) {
                var expression = obj.type === type;

                if (expression && id !== undefined) {
                    expression = expression && obj.id === id;
                }

                if (expression) {
                    found = obj;
                }
            });

            return found;
        };

        $scope.findObjectField = function (object, field) {
            var found;

            angular.forEach(object.fields, function (f) {
                if (f.fieldKey === field) {
                    found = f;
                }
            });

            return found;
        };

        $scope.actionNameCssClass = function (prop) {
            var msg = "control-group";

            if (!prop.name) {
                msg = msg.concat(' error');
            }

            return msg;
        };

        $scope.showLookupValueModal = function (ds, obj) {
            var array = {}, dataSource, object, i, j;

            for (i = 0; i < $scope.selectedDataSources.length; i += 1) {
                dataSource = $scope.selectedDataSources[i];

                if (ds.name !== dataSource.name) {
                    array[dataSource.name] = dataSource.objects;
                } else {
                    for (j = 0; j < dataSource.objects.length; j += 1) {
                        object = dataSource.objects[j];

                        if (object.id !== obj.id) {
                            if (array[dataSource.name] === undefined) {
                                array[dataSource.name] = [];
                            }

                            array[dataSource.name].push(object);
                        }
                    }
                }
            }

            $scope.availableLookupValue = {
                dataSourceName: ds.name,
                objectType: obj.type,
                objectId: obj.id,
                selected: obj.lookup.by,
                trigger: $scope.selectedTrigger.eventParameters,
                ad: array
            };

            $('#lookupValueModal').modal('show');
        };

        $scope.closeLookupValueModal = function () {
            delete $scope.availableLookupValue;
            $('#lookupValueModal').modal('hide');
        };

        $scope.selectedObject = function (prefix, lookup, dsName, obj) {
            var val, ds;

            if (prefix === 'trigger') {
                val = prefix + '.' + lookup.eventKey;
            } else if (prefix === 'ad') {
                ds = $scope.findDataSourceByName($scope.selectedDataSources, dsName);

                val = prefix + '.' + ds._id + '.' + obj.type + '#' + obj.id + '.' + lookup.fieldKey;
            }

            return $scope.availableLookupValue.selected === val;
        };

        $scope.getBooleanValue = function (value) {
            return (value === 'true' || value === 'false') ? null : value;
        };

        $scope.setBooleanValue = function (index, value) {
            var span = '<span contenteditable="false" class="badge badge-' + (value ? 'success' : 'important') + '" data-value="' + value + '" data-prefix="other">' + (value ? $scope.msg('yes') : $scope.msg('no')) + '</span>';
            $scope.selectedAction.actionParameters[index].value = span;
        };

        $scope.checkedBoolean = function (index, val) {
             var prop = $scope.selectedAction.actionParameters[index],
                 value = $scope.refactorDivEditable(prop.value === undefined ? '' : prop.value);

             return value === val;
        };

        $scope.getTaskValidationError = function (error) {
            var array = [], i;

            for (i = 0; i < error.args.length; i += 1) {
                array.push($scope.msg(error.args[i]));
            }

            return $scope.msg(error.message, array);
        };
    });

    widgetModule.controller('LogCtrl', function ($scope, Tasks, Activities, $routeParams, $filter) {
        var data, task, searchMatch = function (activity, filterHistory) {
            var result;

            if (filterHistory === $scope.histories[0]) {
                result = true;
            } else {
                result = activity.activityType === filterHistory;
            }

            return result;
        };

        $scope.resetItemsPagination();
        $scope.filteredItems = [];
        $scope.limitPages = [10, 20, 50, 100];
        $scope.itemsPerPage = $scope.limitPages[0];
        $scope.histories = ['ALL', 'WARNING', 'SUCCESS', 'ERROR'];
        $scope.filterHistory = $scope.histories[0];

        if ($routeParams.taskId !== undefined) {
            data = { taskId: $routeParams.taskId };

            task = Tasks.get(data, function () {
                $scope.activities = Activities.query(data, $scope.search);

                setInterval(function () {
                    $scope.activities = Activities.query(data);
                }, 30 * 1000);

                $scope.trigger = {
                    display: task.trigger.channelName,
                    module: task.trigger.moduleName,
                    version: task.trigger.moduleVersion
                };

                $scope.action = {
                    display: task.action.channelName,
                    module: task.action.moduleName,
                    version: task.action.moduleVersion
                };

                $scope.description = task.description;
                $scope.enabled = task.enabled;
                $scope.name = task.name;
            });
        }

        $scope.changeItemsPerPage = function () {
            $scope.setCurrentPage(0);
            $scope.groupToPages($scope.filteredItems, $scope.itemsPerPage);
        };

        $scope.changeFilterHistory = function () {
            $scope.search();
        };

        $scope.search = function () {
            $scope.filteredItems = $filter('filter')($scope.activities, function (activity) {
                return activity && searchMatch(activity, $scope.filterHistory);
            });

            $scope.setCurrentPage(0);
            $scope.groupToPages($scope.filteredItems, $scope.itemsPerPage);
        };

        $scope.clearHistory = function () {
            motechConfirm('history.confirm.clearHistory', 'history.confirm.clear',function (r) {
                if (!r) {
                    return;
                }
                Activities.remove({taskId: $routeParams.taskId});
                $scope.activities = [];
                $scope.search();
            });
        };
    });
}());
