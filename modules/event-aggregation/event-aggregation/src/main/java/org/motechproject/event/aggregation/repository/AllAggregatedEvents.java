package org.motechproject.event.aggregation.repository;

import org.ektorp.BulkDeleteDocument;
import org.ektorp.ComplexKey;
import org.ektorp.support.View;
import org.motechproject.commons.couchdb.dao.MotechBaseRepository;
import org.motechproject.event.aggregation.model.Aggregation;
import org.motechproject.event.aggregation.model.event.AggregatedEventRecord;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.ektorp.ComplexKey.emptyObject;

@Repository
public class AllAggregatedEvents extends MotechBaseRepository<AggregatedEventRecord> {


    public AllAggregatedEvents() {
        super("motech-event-aggregation",AggregatedEventRecord.class);
    }

    private static final String FIND =
        "function(doc) {                                                                                      \n" +
            "   if (doc.type === 'AggregatedEvent') {                                                             \n" +
            "       emit([doc.aggregationRuleName, doc.aggregationParams, doc.nonAggregationParams], doc._id);    \n" +
            "   }                                                                                                 \n" +
            "}";

    @View(name = "by_rule_and_event_params", map = FIND)
    public AggregatedEventRecord find(String aggregationRuleName, Map<String, Object> aggregationParams, Map<String, Object> nonAggregationParams) {
        return singleResult(queryView("by_rule_and_event_params", ComplexKey.of(aggregationRuleName, aggregationParams, nonAggregationParams)));
    }

    private static final String BY_ERROR_STATE =
        "function(doc) {                                                           \n" +
            "   if (doc.type === 'AggregatedEvent') {                              \n" +
            "       emit([doc.aggregationRuleName, doc.hasError], doc._id);        \n" +
            "   }                                                                  \n" +
            "}";

    @View(name = "by_error_state", map = BY_ERROR_STATE)
    public List<AggregatedEventRecord> findAllByErrorState(String aggregationRuleName, boolean hasError) {
        return queryView("by_error_state", ComplexKey.of(aggregationRuleName, hasError));
    }

    public List<AggregatedEventRecord> findAllAggregated(String aggregationRuleName) {
        return findAllByErrorState(aggregationRuleName, false);
    }

    public List<AggregatedEventRecord> findAllErrored(String aggregationRuleName) {
        return findAllByErrorState(aggregationRuleName, true);
    }

    private static final String VALID_EVENTS_BY_AGGREGATION_FIELDS =
        "function(doc) {                                                                                                                        \n" +
            "   if (doc.type === 'AggregatedEvent' && doc.hasError === false) {                                                                 \n" +
            "       emit([doc.aggregationRuleName, [doc.aggregationParams]], [doc.nonAggregationParams, doc.timeStamp, doc._id, doc._rev]);     \n" +
            "   }                                                                                                                               \n" +
            "}";
    private static final String ERROR_EVENTS_BY_AGGREGATION_FIELDS =
        "function(doc) {                                                                                                 \n" +
    "   if (doc.type === 'AggregatedEvent' && doc.hasError === true) {                                                   \n" +
        "       emit([doc.aggregationRuleName, [doc.aggregationParams]], [doc.nonAggregationParams, doc.timeStamp]);     \n" +
        "   }                                                                                                            \n" +
        "}";
    private static final String GROUP =
        "function(keys, values, rereduce) {                                 \n" +
        "   if (rereduce) {                                                 \n" +
        "      var events = [];                                             \n" +
        "      for (var i = 0; i < values.length; i++) {                    \n" +
        "         events.push.apply(events, values[i].events);              \n" +
        "      }                                                            \n" +
        "      return {                                                     \n" +
        "         \"aggregationRuleName\": values[0].aggregationRuleName,   \n" +
        "         \"events\": events                                        \n" +
        "      };                                                           \n" +
        "   }                                                               \n" +
        "   var events = [];                                                \n" +
        "   for (var i = 0; i < values.length; i++) {                       \n" +
        "       events[i] = {                                               \n" +
        "           \"aggregationParams\"    : keys[0][0][1][0],            \n" +
        "           \"nonAggregationParams\" : values[i][0],                \n" +
        "           \"timeStamp\"            : values[i][1],                \n" +
        "           \"_id\"                  : values[i][2],                \n" +
        "           \"_rev\"                 : values[i][3]                 \n" +
        "       };                                                          \n" +
        "   }                                                               \n" +
        "   return {                                                        \n" +
        "       \"aggregationRuleName\" : keys[0][0][0],                    \n" +
        "       \"events\"              : events                            \n" +
        "   }                                                               \n" +
        "}";
    @View(name = "valid_events_by_aggregation_fields", map = VALID_EVENTS_BY_AGGREGATION_FIELDS, reduce = GROUP)
    public List<Aggregation> findAllAggregations(String aggregationRuleName) {
        return getDb().queryView(createQuery("valid_events_by_aggregation_fields")
            .startKey(ComplexKey.of(aggregationRuleName, null))
            .endKey(ComplexKey.of(aggregationRuleName, emptyObject()))
            .group(true), Aggregation.class);
    }

    @View(name = "error_events_by_aggregation_fields", map = ERROR_EVENTS_BY_AGGREGATION_FIELDS, reduce = GROUP)
    public List<Aggregation> findAllErrorEventsForAggregations(String aggregationRuleName) {
        return getDb().queryView(createQuery("error_events_by_aggregation_fields")
            .startKey(ComplexKey.of(aggregationRuleName, null))
            .endKey(ComplexKey.of(aggregationRuleName, emptyObject()))
            .group(true), Aggregation.class);
    }

    private static final String FIND_ALL_BY_AGGREGATION_RULE =
        "function(doc) {                                       \n" +
            "   if (doc.type === 'AggregatedEvent') {          \n" +
            "       emit(doc.aggregationRuleName, doc._id);    \n" +
            "   }                                              \n" +
            "}";

    @View(name = "by_aggregation_rule", map = FIND_ALL_BY_AGGREGATION_RULE)
    public List<AggregatedEventRecord> findByAggregationRule(String aggregationRuleName) {
        return queryView("by_aggregation_rule", aggregationRuleName);
    }

    public void removeByAggregationRule(String aggregationRule) {
        List<BulkDeleteDocument> docs = new ArrayList<>();
        for (AggregatedEventRecord aggregatedEvent : findByAggregationRule(aggregationRule)) {
            docs.add(BulkDeleteDocument.of(aggregatedEvent));
        }
        getDb().executeBulk(docs);
    }

    public void removeByAggregation(Aggregation aggregation) {
        List<BulkDeleteDocument> docs = new ArrayList<>();

        for (AggregatedEventRecord event : aggregation.getEventRecords()) {
            docs.add(BulkDeleteDocument.of(event));
        }
        getDb().executeBulk(docs);
    }
}
