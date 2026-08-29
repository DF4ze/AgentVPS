package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.model.RecurringTaskStore;

/** Persistance du RecurringTaskStore (voir JsonRecurringTaskStoreRepository, pattern ProjectStoreRepository). */
public interface RecurringTaskStoreRepository {

    RecurringTaskStore load();

    void save(RecurringTaskStore store);
}
