/* Copyright (c) 2013-2016 Boundless and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/edl-v10.html
 *
 * Contributors:
 * Gabriel Roldan (Boundless) - initial implementation
 */
package org.locationtech.geogig.geotools.data;

import java.io.IOException;
import java.util.Set;

import org.eclipse.jdt.annotation.Nullable;
import org.geotools.data.FeatureReader;
import org.geotools.data.FeatureSource;
import org.geotools.data.Query;
import org.geotools.data.QueryCapabilities;
import org.geotools.data.Transaction;
import org.geotools.data.store.ContentEntry;
import org.geotools.data.store.ContentFeatureSource;
import org.geotools.data.store.ContentState;
import org.geotools.factory.Hints;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.filter.visitor.SimplifyingFilterVisitor;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.renderer.ScreenMap;
import org.locationtech.geogig.geotools.data.GeoGigDataStore.ChangeType;
import org.locationtech.geogig.geotools.data.reader.FeatureReaderBuilder;
import org.locationtech.geogig.model.ObjectId;
import org.locationtech.geogig.model.RevFeatureType;
import org.locationtech.geogig.model.RevTree;
import org.locationtech.geogig.plumbing.RevObjectParse;
import org.locationtech.geogig.repository.Context;
import org.locationtech.geogig.repository.NodeRef;
import org.locationtech.geogig.repository.WorkingTree;
import org.opengis.feature.Feature;
import org.opengis.feature.FeatureVisitor;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.Name;
import org.opengis.filter.Filter;
import org.opengis.filter.sort.SortBy;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.vividsolutions.jts.geom.GeometryFactory;

/**
 *
 */
class GeogigFeatureSource extends ContentFeatureSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(GeogigFeatureSource.class);

    private GeoGigDataStore.ChangeType changeType;

    private String oldRoot;

    /**
     * <b>Precondition</b>: {@code entry.getDataStore() instanceof GeoGigDataStore}
     * 
     * @param entry
     */
    public GeogigFeatureSource(ContentEntry entry) {
        this(entry, (Query) null);
    }

    /**
     * <b>Precondition</b>: {@code entry.getDataStore() instanceof GeoGigDataStore}
     * 
     * @param entry
     * @param query optional "definition query" making this feature source a "view"
     */
    public GeogigFeatureSource(ContentEntry entry, @Nullable Query query) {
        super(entry, query);
        Preconditions.checkArgument(entry.getDataStore() instanceof GeoGigDataStore);
    }

    /**
     * Adds the {@link Hints#FEATURE_DETACHED} hint to the supported hints so the renderer doesn't
     * clone the geometries
     */
    @Override
    protected void addHints(Set<Hints.Key> hints) {
        hints.add(Hints.FEATURE_DETACHED);
        hints.add(Hints.SCREENMAP);
        hints.add(Hints.JTS_GEOMETRY_FACTORY);
    }

    @Override
    protected boolean canFilter() {
        return true;
    }

    @Override
    protected boolean canSort() {
        return true;
    }

    @Override
    protected boolean canRetype() {
        return false;
    }

    @Override
    protected boolean canLimit() {
        return true;
    }

    @Override
    protected boolean canOffset() {
        return true;
    }

    /**
     * @return {@code true}
     */
    @Override
    protected boolean canTransact() {
        return true;
    }

    @Override
    protected boolean handleVisitor(Query query, FeatureVisitor visitor) throws IOException {
        return false;
    }

    @Override
    public GeoGigDataStore getDataStore() {
        return (GeoGigDataStore) super.getDataStore();
    }

    @Override
    public ContentState getState() {
        return super.getState();
    }

    /**
     * Overrides {@link ContentFeatureSource#getName()} to restore back the original meaning of
     * {@link FeatureSource#getName()}
     */
    @Override
    public Name getName() {
        return getEntry().getName();
    }

    /**
     * Creates a {@link QueryCapabilities} that declares support for
     * {@link QueryCapabilities#isUseProvidedFIDSupported() isUseProvidedFIDSupported}, the
     * datastore supports using the provided feature id in the data insertion workflow as opposed to
     * generating a new id, by looking into the user data map ( {@link Feature#getUserData()}) for a
     * {@link Hints#USE_PROVIDED_FID} key associated to a {@link Boolean#TRUE} value, if the
     * key/value pair is there an attempt to use the provided id will be made, and the operation
     * will fail if the key cannot be parsed into a valid storage identifier.
     */
    @Override
    protected QueryCapabilities buildQueryCapabilities() {
        return new QueryCapabilities() {
            /**
             * @return {@code true}
             */
            @Override
            public boolean isUseProvidedFIDSupported() {
                return true;
            }

            /**
             * 
             * @return {@code false} by now, will see how/whether we'll support
             *         {@link Query#getVersion()} later
             */
            @Override
            public boolean isVersionSupported() {
                return false;
            }

        };
    }

    @Override
    protected ReferencedEnvelope getBoundsInternal(Query query) throws IOException {
        final Filter filter = (Filter) query.getFilter().accept(new SimplifyingFilterVisitor(),
                null);
        final CoordinateReferenceSystem crs = getSchema().getCoordinateReferenceSystem();
        if (Filter.INCLUDE.equals(filter) && oldRoot == null
                && ChangeType.ADDED.equals(changeType())) {
            NodeRef typeRef = getTypeRef();
            ReferencedEnvelope bounds = new ReferencedEnvelope(crs);
            typeRef.getNode().expand(bounds);
            return bounds;
        }
        if (Filter.EXCLUDE.equals(filter)) {
            return ReferencedEnvelope.create(crs);
        }

        query = new Query(query);
        query.setPropertyNames(Query.NO_NAMES);

        ReferencedEnvelope bounds = new ReferencedEnvelope(crs);
        try (FeatureReader<SimpleFeatureType, SimpleFeature> features = getReaderInternal(query)) {
            while (features.hasNext()) {
                bounds.expandToInclude((ReferencedEnvelope) features.next().getBounds());
            }
        }

        return bounds;
    }

    @Override
    protected int getCountInternal(Query query) throws IOException {
        final Filter filter = (Filter) query.getFilter().accept(new SimplifyingFilterVisitor(),
                null);
        if (Filter.EXCLUDE.equals(filter)) {
            return 0;
        }

        final Integer offset = query.getStartIndex();
        final Integer maxFeatures = query.getMaxFeatures() == Integer.MAX_VALUE ? null
                : query.getMaxFeatures();

        int size;
        if (Filter.INCLUDE.equals(filter) && oldRoot == null
                && ChangeType.ADDED.equals(changeType())) {
            RevTree tree = getTypeTree();
            size = (int) tree.size();
            if (offset != null) {
                size = size - offset.intValue();
            }
            if (maxFeatures != null) {
                size = Math.min(size, maxFeatures.intValue());
            }
            return size;
        }

        query = new Query(query);
        query.setPropertyNames(Query.NO_NAMES);
        query.setSortBy(null);

        int count = 0;
        try (FeatureReader<SimpleFeatureType, SimpleFeature> features = getReaderInternal(query)) {
            while (features.hasNext()) {
                features.next();
                count++;
            }
        }
        return count;
    }

    @Override
    protected FeatureReader<SimpleFeatureType, SimpleFeature> getReaderInternal(final Query query)
            throws IOException {

        final Context context = getCommandLocator();

        final Hints hints = query.getHints();
        final @Nullable GeometryFactory geometryFactory = (GeometryFactory) hints
                .get(Hints.JTS_GEOMETRY_FACTORY);
        final @Nullable Integer offset = query.getStartIndex();
        final @Nullable Integer limit = query.isMaxFeaturesUnlimited() ? null
                : query.getMaxFeatures();
        final @Nullable ScreenMap screenMap = (ScreenMap) hints.get(Hints.SCREENMAP);
        final @Nullable String[] propertyNames = query.getPropertyNames();
        final @Nullable SortBy[] sortBy = query.getSortBy();

        final Filter filter = query.getFilter();

        final SimpleFeatureType fullSchema = getAbsoluteSchema();

        FeatureReaderBuilder builder = FeatureReaderBuilder.builder(context, fullSchema);

        FeatureReader<SimpleFeatureType, SimpleFeature> featureReader = builder//
                .filter(filter)//
                .headRef(getRootRef())//
                .oldHeadRef(oldRoot())//
                .changeType(changeType())//
                .geometryFactory(geometryFactory)//
                .filter(filter)//
                .offset(offset)//
                .limit(limit)//
                .propertyNames(propertyNames)//
                .screenMap(screenMap)//
                .sortBy(sortBy)//
                .build();

        return featureReader;

    }

    private boolean isNaturalOrder(@Nullable SortBy[] sortBy) {
        if (sortBy == null || sortBy.length == 0
                || (sortBy.length == 1 && SortBy.NATURAL_ORDER.equals(sortBy[0]))) {
            return true;
        }
        return false;
    }

    /**
     * @param propertyNames properties to retrieve, empty array for no properties at all
     *        {@link Query#NO_NAMES}, {@code null} means all properties {@link Query#ALL_NAMES}
     */
    // private FeatureReader<SimpleFeatureType, SimpleFeature> getNativeReader(
    // @Nullable String[] propertyNames, Filter filter, @Nullable Integer offset,
    // @Nullable Integer maxFeatures, @Nullable Hints hints) {
    //
    // LOGGER.trace("Query filter: {}", filter);
    // filter = (Filter) filter.accept(new SimplifyingFilterVisitor(), null);
    // LOGGER.trace("Simplified filter: {}", filter);
    //
    // GeogigFeatureReader<SimpleFeatureType, SimpleFeature> nativeReader;
    //
    // final String rootRef = getRootRef();
    // final String featureTypeTreePath = getTypeTreePath();
    //
    // final SimpleFeatureType fullType = getSchema();
    //
    // boolean ignoreAttributes = false;
    // if (propertyNames != null && propertyNames.length == 0) {
    // String[] inProcessFilteringAttributes = Filters.attributeNames(filter, fullType);
    // ignoreAttributes = inProcessFilteringAttributes.length == 0;
    // }
    //
    // final String compareRootRef = oldRoot();
    // final GeoGigDataStore.ChangeType changeType = changeType();
    // final Context context = getCommandLocator();
    //
    // ScreenMap screenMap = null;
    // GeometryFactory geomFac = null;
    // if (hints != null) {
    // screenMap = (ScreenMap) hints.get(Hints.SCREENMAP);
    // geomFac = (GeometryFactory) hints.get(Hints.JTS_GEOMETRY_FACTORY);
    // }
    // nativeReader = new GeogigFeatureReader<SimpleFeatureType, SimpleFeature>(context, fullType,
    // filter, featureTypeTreePath, rootRef, compareRootRef, changeType, offset,
    // maxFeatures, screenMap, ignoreAttributes, geomFac);
    // return nativeReader;
    // }

    public void setChangeType(GeoGigDataStore.ChangeType changeType) {
        this.changeType = changeType;
    }

    public void setOldRoot(@Nullable String oldRoot) {
        this.oldRoot = oldRoot;
    }

    private String oldRoot() {
        return oldRoot == null ? ObjectId.NULL.toString() : oldRoot;
    }

    private GeoGigDataStore.ChangeType changeType() {
        return changeType == null ? ChangeType.ADDED : changeType;
    }

    @Override
    protected SimpleFeatureType buildFeatureType() throws IOException {

        SimpleFeatureType featureType = getNativeType();

        final Name name = featureType.getName();
        final Name assignedName = getEntry().getName();

        if (assignedName.getNamespaceURI() != null && !assignedName.equals(name)) {
            SimpleFeatureTypeBuilder builder = new SimpleFeatureTypeBuilder();
            builder.init(featureType);
            builder.setName(assignedName);
            featureType = builder.buildFeatureType();
        }
        return featureType;
    }

    Context getCommandLocator() {
        Context commandLocator = getDataStore().getCommandLocator(getTransaction());
        return commandLocator;
    }

    SimpleFeatureType getNativeType() {

        final NodeRef typeRef = getTypeRef();
        final String treePath = typeRef.path();
        final ObjectId metadataId = typeRef.getMetadataId();

        Context commandLocator = getCommandLocator();
        Optional<RevFeatureType> revType = commandLocator.command(RevObjectParse.class)
                .setObjectId(metadataId).call(RevFeatureType.class);

        if (!revType.isPresent()) {
            throw new IllegalStateException(
                    String.format("Feature type for tree %s not found", treePath));
        }

        SimpleFeatureType featureType = (SimpleFeatureType) revType.get().type();
        return featureType;
    }

    String getTypeTreePath() {
        NodeRef typeRef = getTypeRef();
        String path = typeRef.path();
        return path;
    }

    /**
     * @return
     */
    NodeRef getTypeRef() {
        GeoGigDataStore dataStore = getDataStore();
        Name name = getName();
        Transaction transaction = getTransaction();
        return dataStore.findTypeRef(name, transaction);
    }

    /**
     * @return
     */
    RevTree getTypeTree() {
        String refSpec = getRootRef() + ":" + getTypeTreePath();
        Context commandLocator = getCommandLocator();
        Optional<RevTree> ref = commandLocator.command(RevObjectParse.class).setRefSpec(refSpec)
                .call(RevTree.class);
        Preconditions.checkState(ref.isPresent(), "Ref %s not found on working tree", refSpec);
        return ref.get();
    }

    private String getRootRef() {
        GeoGigDataStore dataStore = getDataStore();
        Transaction transaction = getTransaction();
        return dataStore.getRootRef(transaction);
    }

    /**
     * @return
     */
    WorkingTree getWorkingTree() {
        Context commandLocator = getCommandLocator();
        WorkingTree workingTree = commandLocator.workingTree();
        return workingTree;
    }
}
