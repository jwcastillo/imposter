package io.gatehill.imposter.store.model;

import java.util.Map;

/**
 * @author Pete Cornish {@literal <outofcoffee@gmail.com>}
 */
public interface Store {
    String getTypeDescription();

    void save(String key, Object value);

    <T> T load(String key);

    void delete(String key);

    Map<String, Object> loadAll();

    boolean hasItemWithKey(String key);
}
