package com.couchbase.lite.store;

import com.couchbase.cbforest.Constants;
import com.couchbase.cbforest.Database;
import com.couchbase.cbforest.Document;
import com.couchbase.cbforest.DocumentIterator;
import com.couchbase.cbforest.ForestException;
import com.couchbase.cbforest.QueryIterator;
import com.couchbase.cbforest.View;
import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.Emitter;
import com.couchbase.lite.Manager;
import com.couchbase.lite.Mapper;
import com.couchbase.lite.Misc;
import com.couchbase.lite.Predicate;
import com.couchbase.lite.QueryOptions;
import com.couchbase.lite.QueryRow;
import com.couchbase.lite.Reducer;
import com.couchbase.lite.Status;
import com.couchbase.lite.internal.RevisionInternal;
import com.couchbase.lite.support.FileDirUtils;
import com.couchbase.lite.util.Log;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by hideki on 8/13/15.
 */
public class ForestDBViewStore  implements ViewStore, QueryRowStore, Constants{
    public static String TAG = Log.TAG_VIEW;

    public static final String  kViewIndexPathExtension = "viewindex";
    // Size of ForestDB buffer cache allocated for a database
    private static final BigInteger kDBBufferCacheSize = new BigInteger("8388608");

    // ForestDB Write-Ahead Log size (# of records)
    private static final BigInteger kDBWALThreshold = new BigInteger("1024");

    // Close the index db after it's inactive this many seconds
    private static final Float kCloseDelay = 60.0f;

    private static final int REDUCE_BATCH_SIZE = 100;

    /**
     * in CBLView.h
     * enum CBLViewIndexType
     */
    public enum CBLViewIndexType {
        kUnknown(-1),
        kCBLMapReduceIndex(1), // < Regular map/reduce _index with JSON keys.
        kCBLFullTextIndex(2),  // < Keys must be strings and will be indexed by the words they contain.
        kCBLGeoIndex(3);       // < Geo-query _index; not supported yet.
        private int value;
        CBLViewIndexType(int value) {
            this.value = value;
        }
        public int getValue() {
            return value;
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // ForestDBViewStore
    ///////////////////////////////////////////////////////////////////////////

    // public
    private String name;
    private ViewStoreDelegate delegate;

    // private
    private ForestDBStore _dbStore;
    private String _path;
    private View _index;

    ///////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////

    protected ForestDBViewStore(ForestDBStore dbStore, String name, boolean create) throws CouchbaseLiteException{
        Log.w(TAG, "ForestDBViewStore()");
        this._dbStore = dbStore;
        this.name = name;
        this._path = new File(dbStore.directory, viewNameToFileName(name)).getPath();
        File file = new File(this._path);
        if(!file.exists()){
            if(!create)
                throw new CouchbaseLiteException(Status.NOT_FOUND);
            try {
                openIndex(Database.Create, true);
            } catch (ForestException e) {
                throw new CouchbaseLiteException(e.code);
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Implementation of ViewStorage
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public boolean rowValueIsEntireDoc(byte[] valueData) {
        Log.e(TAG, "rowValueIsEntireDoc()");
        return false;
    }

    @Override
    public Object parseRowValue(byte[] valueData) {
        Log.e(TAG, "parseRowValue()");
        return null;
    }

    @Override
    public Map<String, Object> getDocumentProperties(String docID, long sequence) {
        Log.e(TAG, "getDocumentProperties()");
        return null;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ViewStoreDelegate getDelegate() {
        return delegate;
    }

    @Override
    public void setDelegate(ViewStoreDelegate delegate) {
        this.delegate = delegate;
    }

    @Override
    public void close() {
        Log.w(TAG, "ForestDBViewStore.close()");
        closeIndex();
    }

    @Override
    public void deleteIndex() {
        Log.w(TAG, "deleteIndex()");
        if(_index!=null) _index.closeView();
    }

    @Override
    public void deleteView() {
        Log.w(TAG, "ForestDBViewStore.deleteView()");
        closeIndex();
        FileDirUtils.deleteRecursive(new File(_path));
    }

    @Override
    public boolean setVersion(String version) {
        return true;
    }

    @Override
    public int getTotalRows() {
        Log.w(TAG, "ForestDBViewStore.getTotalRows()");
        try {
            openIndex();
        } catch (ForestException e) {
            Log.e(TAG, "Exception opening index while getting total rows", e);
            return -1;
        }
        return (int)_index.getTotalRows();
    }

    @Override
    public long getLastSequenceIndexed() {
        Log.w(TAG, "ForestDBViewStore.getLastSequenceIndexed()");
        try {
            openIndex(); // in case the _mapVersion changed, invalidating the _index
        } catch (ForestException e) {
            Log.e(TAG, "Exception opening index while getting last sequence indexed", e);
            return -1;
        }
        return _index.getLastSequenceIndexed();
    }

    @Override
    public long getLastSequenceChangedAt() {
        Log.w(TAG, "getLastSequenceChangedAt()");
        try {
            openIndex(); // in case the _mapVersion changed, invalidating the _index
        } catch (ForestException e) {
            Log.e(TAG, "Exception opening index while getting last sequence changed at", e);
            return -1;
        }
        return _index.getLastSequenceChangedAt();
    }

    @Override
    public void updateIndex() throws CouchbaseLiteException {
        Log.w(TAG, "ForestDBViewStore.updateIndex()");
        updateIndex(Arrays.asList(this));
    }

    private int updateIndex(List<ForestDBViewStore> views) throws CouchbaseLiteException {
        Log.w(TAG, "ForestDBViewStore.updateIndex(List)");
        Log.v(TAG, "Checking indexes of (%s) for %s", viewNames(views), this.getName());

        try {
            openIndex();
        } catch (ForestException e) {
            throw new CouchbaseLiteException(e.code);
        }

        if (this.delegate.getMap() == null) {
            Log.v(TAG, "    %s has no map block; skipping it", this.getName());
            return Status.OK;
        }

        try {
            boolean commit = false;
            _index.beginIndex();
            try {
                DocumentIterator itr = null;
                try {
                    itr = _index.enumerator();
                }catch(ForestException e){
                    if(e.code == FDBErrors.FDB_RESULT_SUCCESS)
                        return Status.NOT_MODIFIED;
                    else
                        throw e;
                }
                if(itr != null) {
                    try {
                        Document doc;
                        while ((doc = itr.nextDocument()) != null) {
                            boolean indexIt = true;
                            if(doc.deleted())
                                indexIt = false;
                            if(doc.getDocID().startsWith("_design/"))
                                indexIt = false;
                            final List<Object> keys = new ArrayList<>();
                            final List<byte[]> values = new ArrayList<>();
                            if(indexIt) {
                                RevisionInternal rev = ForestBridge.revisionObjectFromForestDoc(doc, null, true);
                                Mapper map = this.delegate.getMap();
                                if (map != null) {
                                    map.map(rev.getProperties(), new Emitter() {
                                        @Override
                                        public void emit(Object key, Object value) {
                                            byte[] json = new byte[0];
                                            try {
                                                json = Manager.getObjectMapper().writeValueAsBytes(value);
                                            } catch (JsonProcessingException e) {
                                                Log.e(TAG, "Error in obj -> json", e);
                                            }
                                            keys.add(key);
                                            values.add(json);
                                        }
                                    });
                                }
                            }
                            byte[][] jsons = new byte[values.size()][];
                            for(int i = 0; i < values.size(); i++){
                                jsons[i] = values.get(i);
                            }
                            _index.emit(doc, keys.toArray(), jsons);
                            doc.free();
                        }
                        commit = true;
                    } finally {
                        if (itr != null) itr.free();
                    }
                }
            } finally {
                _index.endIndex(commit);
            }
        }catch(ForestException e){
            Log.e(TAG, "Error in updateIndex()", e);
            throw new CouchbaseLiteException(e.code);
        }

        Log.v(TAG, "... Finished re-indexing (%s) to TODO", viewNames(views));
        return Status.OK;
    }

    @Override
    public List<QueryRow> regularQuery(QueryOptions options) throws CouchbaseLiteException {
        Log.w(TAG, "ForestDBViewStore.regularQuery(QueryOptions)");
        try {
            openIndex();
        } catch (ForestException e) {
            Log.e(TAG, "Exception opening index while getting total rows", e);
            throw new CouchbaseLiteException(e.code);
        }

        final Predicate<QueryRow> postFilter = options.getPostFilter();
        int limit = options.getLimit();
        int skip = options.getSkip();
        if (postFilter != null) {
            // #574: Custom post-filter means skip/limit apply to the filtered rows, not to the
            // underlying query, so handle them specially:
            options.setLimit(QueryOptions.QUERY_OPTIONS_DEFAULT_LIMIT);
            options.setSkip(0);
        }

        List<QueryRow> rows = new ArrayList<QueryRow>();

        QueryIterator itr = null;
        try {
            itr = runForestQuery(options);
            while (itr.next()) {
                RevisionInternal docRevision = null;
                byte[] bKey = itr.keyJSON();
                byte[] bValue = itr.valueJSON();
                Object key = bKey != null ? Manager.getObjectMapper().readValue(bKey, Object.class) : null;
                Object value = bValue != null ? Manager.getObjectMapper().readValue(bValue, Object.class) : null;
                String docID = itr.docID();
                long sequence = itr.sequence();
                if(options.isIncludeDocs()){
                    String linkedID = null;
                    if(value instanceof Map)
                        linkedID = (String) ((Map) value).get("_id");
                    if (linkedID != null) {
                        // Linked document: http://wiki.apache.org/couchdb/Introduction_to_CouchDB_views#Linked_documents
                        String linkedRev = (String) ((Map) value).get("_rev");
                        docRevision = _dbStore.getDocument(linkedID, linkedRev, true);
                        sequence = docRevision.getSequence();
                    } else {
                        docRevision = _dbStore.getDocument(docID, null, true);
                    }
                }
                Log.d(TAG, "Query %s: Found row with key=%s, value=%s, id=%s",
                        name,
                        key == null ? "" : key,
                        value == null ? "" : value,
                        docID);
                QueryRow row = new QueryRow(docID, sequence,
                        key, value,
                        docRevision, this);

                if (postFilter != null) {
                    if (!postFilter.apply(row)) {
                        continue;
                    }
                }

                rows.add(row);
            }
        }
        catch (ForestException e){
            Log.e(TAG, "Error in regularQuery()", e);
            throw new CouchbaseLiteException(e.code);
        }
        catch (IOException e){
            Log.e(TAG, "Error in regularQuery()", e);
            throw new CouchbaseLiteException(Status.UNKNOWN);
        }
        finally {
            itr.free();
        }
        return rows;

    }

    /**
     * Queries the view, with reducing or grouping as per the options.
     * in CBL_ForestDBViewStorage.m
     * - (CBLQueryIteratorBlock) reducedQueryWithOptions: (CBLQueryOptions*)options
     *                                            status: (CBLStatus*)outStatus
     */
    @Override
    public List<QueryRow> reducedQuery(QueryOptions options) throws CouchbaseLiteException {
        Log.w(TAG, "ForestDBViewStore.reducedQuery(QueryOptions)");

        final Predicate<QueryRow> postFilter = options.getPostFilter();

        final int groupLevel = options.getGroupLevel();
        final boolean group = options.isGroup() || (groupLevel > 0);
        final Reducer reduce = delegate.getReduce();
        if (options.isReduceSpecified()) {
            if (options.isReduce() && reduce == null) {
                Log.w(TAG, String.format(
                        "Cannot use reduce option in view %s which has no reduce block defined",
                        name));
                throw new CouchbaseLiteException(new Status(Status.BAD_PARAM));
            }
        }

        final List<Object> keysToReduce = new ArrayList<Object>(REDUCE_BATCH_SIZE);
        final List<Object> valuesToReduce = new ArrayList<Object>(REDUCE_BATCH_SIZE);
        final Object[] lastKeys = new Object[1];
        lastKeys[0] = null;
        final ForestDBViewStore that = this;
        final List<QueryRow> rows = new ArrayList<QueryRow>();

        try {
            openIndex();
        } catch (ForestException e) {
            throw new CouchbaseLiteException(e.code);
        }

        QueryIterator itr = null;
        try {
            itr = runForestQuery(options);
            while(itr.next()){
                RevisionInternal docRevision = null;
                byte[] bKey = itr.keyJSON();
                byte[] bValue = itr.valueJSON();
                Object keyObject = bKey != null ? Manager.getObjectMapper().readValue(bKey, Object.class) : null;
                Object valueObject = bValue != null ? Manager.getObjectMapper().readValue(bValue, Object.class) : null;
                if (group && !groupTogether(keyObject, lastKeys[0], groupLevel)) {
                    if (lastKeys[0] != null) {
                        // This pair starts a new group, so reduce & record the last one:
                        Object key = groupKey(lastKeys[0], groupLevel);
                        Object reduced = (reduce != null) ?
                                reduce.reduce(keysToReduce, valuesToReduce, false) :
                                null;
                        QueryRow row = new QueryRow(null, 0, key, reduced, null, that);
                        if (postFilter == null || postFilter.apply(row))
                            rows.add(row);
                        keysToReduce.clear();
                        valuesToReduce.clear();
                    }
                    lastKeys[0] = keyObject;
                }

                keysToReduce.add(keyObject);
                valuesToReduce.add(valueObject);
            }
        } catch (ForestException e) {
            Log.e(TAG, "Error in reducedQuery()", e);
        } catch (IOException e){
            Log.e(TAG, "Error in reducedQuery()", e);
            throw new CouchbaseLiteException(Status.UNKNOWN);
        } finally {
            itr.free();
        }

        if (keysToReduce != null && keysToReduce.size() > 0) {
            // Finish the last group (or the entire list, if no grouping):
            Object key = group ? groupKey(lastKeys[0], groupLevel) : null;
            Object reduced = (reduce != null) ?
                    reduce.reduce(keysToReduce, valuesToReduce, false) :
                    null;
            Log.v(TAG, String.format("Query %s: Reduced to key=%s, value=%s",
                    name, key, reduced));
            QueryRow row = new QueryRow(null, 0, key, reduced, null, that);
            if (postFilter == null || postFilter.apply(row)) {
                rows.add(row);
            }
        }
        return rows;
    }

    @Override
    public List<Map<String, Object>> dump() {
        Log.w(TAG, "ForestDBViewStore.dump()");
        try {
            openIndex();
        } catch (ForestException e) {
            Log.e(TAG, "ERROR in openIndex()", e);
            return null;
        }

        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        QueryIterator itr = null;
        try {
            itr = runForestQuery(new QueryOptions());
            while (itr.next()) {
                Map<String, Object> dict = new HashMap<>();
                dict.put("key", new String(itr.keyJSON()));

                byte[] bytes = itr.valueJSON();
                if(bytes != null) {
                    Object obj = Manager.getObjectMapper().readValue(bytes, Object.class);
                    dict.put("value", obj);
                }
                else{
                    dict.put("value", null);
                }
                dict.put("seq", itr.sequence());
                result.add(dict);
            }
        } catch (Exception ex) {
            Log.e(TAG, "Error in dump()", ex);
        } finally {
            if (itr != null) itr.free();
        }
        return result;
    }

    @Override
    public void setCollation(com.couchbase.lite.View.TDViewCollation collation) {
        Log.w(TAG, "This method should be removed");
    }

    ///////////////////////////////////////////////////////////////////////////
    // Internal (Protected/Private)  Methods
    ///////////////////////////////////////////////////////////////////////////

    // Opens the index. You MUST call this (or a method that calls it) before dereferencing _index.
    private View openIndex() throws ForestException {
        return openIndex(Database.Create);
    }

    private View openIndex(int flags) throws ForestException {
        return openIndex(flags, false);
    }

    /**
     * Opens the index, specifying ForestDB database flags
     * in CBLView.m
     * - (MapReduceIndex*) openIndexWithOptions: (Database::openFlags)options
     */
    private View openIndex(int flags, boolean dryRun) throws ForestException {
        if (_index == null) {
            //Log.e(TAG, "[openIndex()] name => `%s`, delegate.getMapVersion() => `%s`", name, delegate!=null?delegate.getMapVersion():null);
            _index = new View(_dbStore.forest, _path, flags, 0, null, name, dryRun ? "0" : delegate.getMapVersion());
            if(dryRun){
                closeIndex();
            }
        }
        return _index;
    }

    /**
     * in CBL_ForestDBViewStorage.mm
     * - (void) closeIndex
     */
    private void closeIndex() {
        // TODO
        //NSObject cancelPreviousPerformRequestsWithTarget: self selector: @selector(closeIndex) object: nil];

        if (_index != null) {
            _index.closeView();
            _index = null;
        }
    }

    private static String viewNames(List<ForestDBViewStore> views) {
        StringBuffer sb = new StringBuffer();
        for (ForestDBViewStore view : views) {
            sb.append(view.getName());
            sb.append(", ");
        }
        return sb.toString();
    }

    /**
     * Starts a view query, returning a CBForest enumerator.
     * - (IndexEnumerator*) _runForestQueryWithOptions: (CBLQueryOptions*)options
     */
    private QueryIterator runForestQuery(QueryOptions options) throws ForestException {
        if(options == null)
            options = new QueryOptions();

        long skip = options.getSkip();
        long limit = options.getLimit();
        boolean descending = options.isDescending();
        boolean inclusiveStart = options.isInclusiveStart();
        boolean inclusiveEnd = options.isInclusiveEnd();
        if (options.getKeys() != null && options.getKeys().size() > 0) {
            Object[] keys = options.getKeys().toArray();
            return _index.query(
                    skip,
                    limit,
                    descending,
                    inclusiveStart,
                    inclusiveEnd,
                    keys);
        } else {
            Object endKey = Misc.keyForPrefixMatch(options.getEndKey(), options.getPrefixMatchLevel());

            Object startKey = options.getStartKey();
            //Object endKey = options.getEndKey();
            String startKeyDocID = options.getStartKeyDocId();
            String endKeyDocID = options.getEndKeyDocId();
            return _index.query(
                    skip,
                    limit,
                    descending,
                    inclusiveStart,
                    inclusiveEnd,
                    startKey,
                    endKey,
                    startKeyDocID,
                    endKeyDocID);
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Internal (Protected/Private) Static Methods
    ///////////////////////////////////////////////////////////////////////////

    protected static String fileNameToViewName(String fileName) {
        if (!fileName.endsWith(kViewIndexPathExtension))
            return null;
        if (fileName.startsWith("."))
            return null;
        String viewName = fileName.substring(0, fileName.indexOf("."));
        viewName = viewName.replaceAll(":", "/");
        return viewName;
    }

    private static String viewNameToFileName(String viewName) {
        if (viewName.startsWith(".") || viewName.indexOf(":") > 0)
            return null;
        viewName = viewName.replaceAll("/", ":");
        return viewName + "." + kViewIndexPathExtension;
    }

    /**
     * Are key1 and key2 grouped together at this groupLevel?
     */
    private static boolean groupTogether(Object key1, Object key2, int groupLevel) {
        if (groupLevel == 0 || !(key1 instanceof List) || !(key2 instanceof List)) {
            return key1.equals(key2);
        }
        @SuppressWarnings("unchecked")
        List<Object> key1List = (List<Object>) key1;
        @SuppressWarnings("unchecked")
        List<Object> key2List = (List<Object>) key2;

        // if either key list is smaller than groupLevel and the key lists are different
        // sizes, they cannot be equal.
        if ((key1List.size() < groupLevel || key2List.size() < groupLevel) &&
                key1List.size() != key2List.size()) {
            return false;
        }

        int end = Math.min(groupLevel, Math.min(key1List.size(), key2List.size()));
        for (int i = 0; i < end; ++i) {
            if (!key1List.get(i).equals(key2List.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the prefix of the key to use in the result row, at this groupLevel
     */
    public static Object groupKey(Object key, int groupLevel) {
        if (groupLevel > 0 && (key instanceof List) && (((List<Object>) key).size() > groupLevel)) {
            return ((List<Object>) key).subList(0, groupLevel);
        } else {
            return key;
        }
    }
}
