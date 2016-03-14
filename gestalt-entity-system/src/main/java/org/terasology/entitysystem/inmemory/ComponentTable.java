/*
 * Copyright 2016 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.entitysystem.inmemory;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import gnu.trove.TCollections;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.iterator.TLongIterator;
import gnu.trove.iterator.TLongObjectIterator;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TLongIntMap;
import gnu.trove.map.TLongObjectMap;
import gnu.trove.map.hash.TLongIntHashMap;
import gnu.trove.map.hash.TLongObjectHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.TLongSet;
import gnu.trove.set.hash.TIntHashSet;
import org.terasology.entitysystem.Component;
import org.terasology.entitysystem.component.ComponentManager;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A table for storing entities and components. Focused on allowing iteration across a components of a given type
 *
 * @author Immortius
 */
class ComponentTable implements EntityStore {
    private static final int DEFAULT_CONCURRENCY_LEVEL = 16;
    private final Map<Class, TLongObjectMap<Component>> store = Maps.newConcurrentMap();
    private final TLongIntMap revisions = TCollections.synchronizedMap(new TLongIntHashMap());
    private final TLongIntMap numComponents = TCollections.synchronizedMap(new TLongIntHashMap());

    private final int concurrencyLevel;
    private final ReentrantLock[] locks;

    private final ComponentManager componentManager;
    private final AtomicLong idSource = new AtomicLong(1);

    public ComponentTable(ComponentManager componentManager) {
        this(componentManager, DEFAULT_CONCURRENCY_LEVEL);
    }

    public ComponentTable(ComponentManager componentManager, int concurrencyLevel) {
        Preconditions.checkArgument(concurrencyLevel > 0, "Concurrency level must be > 0");
        this.componentManager = componentManager;
        this.concurrencyLevel = concurrencyLevel;
        this.locks = new ReentrantLock[concurrencyLevel];
        for (int i = 0; i < concurrencyLevel; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    @Override
    public long createEntityId() {
        return idSource.getAndIncrement();
    }

    @Override
    public int getEntityRevision(long entityId) {
        return revisions.get(entityId);
    }

    @Override
    public ClosableLock lock(TLongSet entityIds) {
        return new CompositeLock(entityIds);
    }

    @Override
    public <T extends Component> T get(long entityId, Class<T> componentClass) {
        TLongObjectMap<Component> entityMap = store.get(componentClass);
        if (entityMap != null) {
            return componentManager.copy(componentClass.cast(entityMap.get(entityId)));
        }
        return null;
    }

    @Override
    public synchronized <T extends Component> boolean add(long entityId, Class<T> componentType, T component) {
        ReentrantLock lock = locks[selectLock(entityId)];
        lock.lock();
        try {
            TLongObjectMap<Component> entityMap = store.get(componentType);
            if (entityMap == null) {
                entityMap = TCollections.synchronizedMap(new TLongObjectHashMap<>());
                store.put(componentType, entityMap);
            }
            revisions.adjustOrPutValue(entityId, 1, 1);
            boolean added = entityMap.putIfAbsent(entityId, componentManager.copy(component)) == null;
            if (added) {
                numComponents.adjustOrPutValue(entityId, 1, 1);
            }
            return added;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public synchronized <T extends Component> boolean update(long entityId, Class<T> componentType, T component) {
        ReentrantLock lock = locks[selectLock(entityId)];
        lock.lock();
        try {
            TLongObjectMap<Component> entityMap = store.get(componentType);
            if (entityMap == null) {
                return false;
            }
            Component existingComponent = entityMap.get(entityId);
            if (existingComponent != null) {
                componentManager.copy(component, existingComponent);
                revisions.increment(entityId);
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return removes the component with the specified class from the entity and returns it.
     * Returns null if no component could be removed.
     */
    @Override
    public <T extends Component> Component remove(long entityId, Class<T> componentClass) {
        ReentrantLock lock = locks[selectLock(entityId)];
        lock.lock();
        try {
            TLongObjectMap<Component> entityMap = store.get(componentClass);
            if (entityMap != null) {
                Component removed = entityMap.remove(entityId);
                if (removed != null) {
                    int remainingComps = numComponents.get(entityId);
                    if (remainingComps == 0) {
                        numComponents.remove(entityId);
                        revisions.remove(entityId);
                    } else {
                        revisions.increment(entityId);
                    }
                }
                return removed;
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear() {
        store.clear();
        revisions.clear();
        numComponents.clear();
        idSource.set(1);

    }

    @Override
    public int getComponentCount(Class<? extends Component> componentClass) {
        TLongObjectMap<Component> map = store.get(componentClass);
        return (map == null) ? 0 : map.size();
    }

    /**
     * @return an iterable that should be only used for iteration over the components. It can't be used to remove
     * components. It should not be used after components have been added or removed from the entity.
     */
    @Override
    public Collection<Component> getComponents(long entityId) {
        List<Component> components = Lists.newArrayList();
        for (TLongObjectMap<Component> componentMap : store.values()) {
            Component comp = componentMap.get(entityId);
            if (comp != null) {
                components.add(componentManager.copy(comp));
            }
        }
        return components;
    }

    @Override
    public <T extends Component> TLongObjectIterator<T> componentIterator(Class<T> componentClass) {
        TLongObjectMap<T> entityMap = (TLongObjectMap<T>) store.get(componentClass);
        if (entityMap != null) {
            return entityMap.iterator();
        }
        return null;
    }

    /**
     * Produces an iterator for iterating over all entities
     * <br><br>
     * This is not designed to be performant, and in general usage entities should not be iterated over.
     *
     * @return An iterator over all entity ids.
     */
    @Override
    public TLongIterator entityIdIterator() {
        return revisions.keySet().iterator();
    }

    @Override
    public int entityCount() {
        return revisions.size();
    }

    @Override
    public boolean isAvailable(long entityId) {
        return revisions.keySet().contains(entityId);
    }

    private int selectLock(long id) {
        int h = Long.hashCode(id);
        h ^= (h >>> 20) ^ (h >>> 12);
        return (h ^ (h >>> 7) ^ (h >>> 4)) % concurrencyLevel;
    }

    /**
     * A lock across multiple entity locks. Locking is sorted to prevent dead lock.
     */
    private class CompositeLock implements ClosableLock {

        private TIntList lockList;

        public CompositeLock(TLongSet entityIds) {
            TIntSet lockIds = new TIntHashSet(entityIds.size());
            TLongIterator entityIterator = entityIds.iterator();
            while (entityIterator.hasNext()) {
                lockIds.add(selectLock(entityIterator.next()));
            }

            lockList = new TIntArrayList(lockIds);
            lockList.sort();
            TIntIterator lockIterator = lockList.iterator();
            while (lockIterator.hasNext()) {
                locks[lockIterator.next()].lock();
            }
        }

        @Override
        public void close() {
            TIntIterator lockIterator = lockList.iterator();
            while (lockIterator.hasNext()) {
                locks[lockIterator.next()].unlock();
            }
        }
    }
}
