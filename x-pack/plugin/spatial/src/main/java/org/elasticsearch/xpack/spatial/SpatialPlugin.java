/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.spatial;

import org.apache.lucene.util.SetOnce;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.common.geo.GeoFormatterFactory;
import org.elasticsearch.common.xcontent.ContextParser;
import org.elasticsearch.geometry.Geometry;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.ingest.Processor;
import org.elasticsearch.license.License;
import org.elasticsearch.license.LicenseUtils;
import org.elasticsearch.license.LicensedFeature;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.ExtensiblePlugin;
import org.elasticsearch.plugins.IngestPlugin;
import org.elasticsearch.plugins.MapperPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.SearchPlugin;
import org.elasticsearch.search.aggregations.bucket.geogrid.GeoHashGridAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.geogrid.GeoTileGridAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.CardinalityAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.CardinalityAggregator;
import org.elasticsearch.search.aggregations.metrics.GeoBoundsAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.GeoCentroidAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.ValueCountAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.ValueCountAggregator;
import org.elasticsearch.search.aggregations.support.ValuesSourceRegistry;
import org.elasticsearch.xpack.core.XPackPlugin;
import org.elasticsearch.xpack.core.action.XPackInfoFeatureAction;
import org.elasticsearch.xpack.core.action.XPackUsageFeatureAction;
import org.elasticsearch.xpack.core.spatial.action.SpatialStatsAction;
import org.elasticsearch.xpack.spatial.action.SpatialInfoTransportAction;
import org.elasticsearch.xpack.spatial.action.SpatialStatsTransportAction;
import org.elasticsearch.xpack.spatial.action.SpatialUsageTransportAction;
import org.elasticsearch.xpack.spatial.index.mapper.GeoShapeWithDocValuesFieldMapper;
import org.elasticsearch.xpack.spatial.index.mapper.PointFieldMapper;
import org.elasticsearch.xpack.spatial.index.mapper.ShapeFieldMapper;
import org.elasticsearch.xpack.spatial.index.query.ShapeQueryBuilder;
import org.elasticsearch.xpack.spatial.ingest.CircleProcessor;
import org.elasticsearch.xpack.spatial.search.aggregations.GeoLineAggregationBuilder;
import org.elasticsearch.xpack.spatial.search.aggregations.InternalGeoLine;
import org.elasticsearch.xpack.spatial.search.aggregations.bucket.geogrid.BoundedGeoHashGridTiler;
import org.elasticsearch.xpack.spatial.search.aggregations.bucket.geogrid.BoundedGeoTileGridTiler;
import org.elasticsearch.xpack.spatial.search.aggregations.bucket.geogrid.GeoGridTiler;
import org.elasticsearch.xpack.spatial.search.aggregations.bucket.geogrid.GeoShapeCellIdSource;
import org.elasticsearch.xpack.spatial.search.aggregations.bucket.geogrid.GeoShapeHashGridAggregator;
import org.elasticsearch.xpack.spatial.search.aggregations.bucket.geogrid.GeoShapeTileGridAggregator;
import org.elasticsearch.xpack.spatial.search.aggregations.bucket.geogrid.UnboundedGeoHashGridTiler;
import org.elasticsearch.xpack.spatial.search.aggregations.bucket.geogrid.UnboundedGeoTileGridTiler;
import org.elasticsearch.xpack.spatial.search.aggregations.metrics.GeoShapeBoundsAggregator;
import org.elasticsearch.xpack.spatial.search.aggregations.metrics.GeoShapeCentroidAggregator;
import org.elasticsearch.xpack.spatial.search.aggregations.support.GeoShapeValuesSource;
import org.elasticsearch.xpack.spatial.search.aggregations.support.GeoShapeValuesSourceType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static java.util.Collections.singletonList;

public class SpatialPlugin extends Plugin implements ActionPlugin, MapperPlugin, SearchPlugin, IngestPlugin, ExtensiblePlugin {
    private final SpatialUsage usage = new SpatialUsage();

    private final LicensedFeature.Momentary GEO_CENTROID_AGG_FEATURE =
        LicensedFeature.momentary("spatial", "geo-centroid-agg", License.OperationMode.GOLD);
    private final LicensedFeature.Momentary GEO_GRID_AGG_FEATURE =
        LicensedFeature.momentary("spatial", "geo-grid-agg", License.OperationMode.GOLD);
    private final LicensedFeature.Momentary GEO_LINE_AGG_FEATURE =
        LicensedFeature.momentary("spatial", "geo-line-agg", License.OperationMode.GOLD);

    // to be overriden by tests
    protected XPackLicenseState getLicenseState() {
        return XPackPlugin.getSharedLicenseState();
    }
    // register the vector tile factory from a different module
    private final SetOnce<GeoFormatterFactory<Geometry>> geoFormatterFactory = new SetOnce<>();

    @Override
    public List<ActionPlugin.ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
        return Arrays.asList(
            new ActionPlugin.ActionHandler<>(XPackUsageFeatureAction.SPATIAL, SpatialUsageTransportAction.class),
            new ActionPlugin.ActionHandler<>(XPackInfoFeatureAction.SPATIAL, SpatialInfoTransportAction.class),
            new ActionPlugin.ActionHandler<>(SpatialStatsAction.INSTANCE, SpatialStatsTransportAction.class));
    }

    @Override
    public Map<String, Mapper.TypeParser> getMappers() {
        return Map.of(
            ShapeFieldMapper.CONTENT_TYPE, ShapeFieldMapper.PARSER,
            PointFieldMapper.CONTENT_TYPE, PointFieldMapper.PARSER,
            GeoShapeWithDocValuesFieldMapper.CONTENT_TYPE, new GeoShapeWithDocValuesFieldMapper.TypeParser(geoFormatterFactory.get())
        );
    }

    @Override
    public List<QuerySpec<?>> getQueries() {
        return singletonList(new QuerySpec<>(ShapeQueryBuilder.NAME, ShapeQueryBuilder::new, ShapeQueryBuilder::fromXContent));
    }

    @Override
    public List<Consumer<ValuesSourceRegistry.Builder>> getAggregationExtentions() {
        return List.of(
            this::registerGeoShapeCentroidAggregator,
            this::registerGeoShapeGridAggregators,
            SpatialPlugin::registerGeoShapeBoundsAggregator,
            SpatialPlugin::registerValueCountAggregator,
            SpatialPlugin::registerCardinalityAggregator
        );
    }

    @Override
    public List<AggregationSpec> getAggregations() {
        return List.of(
            new AggregationSpec(
                    GeoLineAggregationBuilder.NAME,
                    GeoLineAggregationBuilder::new,
                    usage.track(SpatialStatsAction.Item.GEOLINE,
                        checkLicense(GeoLineAggregationBuilder.PARSER, GEO_LINE_AGG_FEATURE)))
                .addResultReader(InternalGeoLine::new)
                .setAggregatorRegistrar(GeoLineAggregationBuilder::registerUsage));
    }

    @Override
    public Map<String, Processor.Factory> getProcessors(Processor.Parameters parameters) {
        return Map.of(CircleProcessor.TYPE, new CircleProcessor.Factory());
    }

    private static void registerGeoShapeBoundsAggregator(ValuesSourceRegistry.Builder builder) {
        builder.register(
            GeoBoundsAggregationBuilder.REGISTRY_KEY,
            GeoShapeValuesSourceType.instance(),
            GeoShapeBoundsAggregator::new,
            true
        );
    }

    private void registerGeoShapeCentroidAggregator(ValuesSourceRegistry.Builder builder) {
        builder.register(GeoCentroidAggregationBuilder.REGISTRY_KEY, GeoShapeValuesSourceType.instance(),
            (name, valuesSourceConfig, context, parent, metadata)
                -> {
                if (GEO_CENTROID_AGG_FEATURE.check(getLicenseState())) {
                    return new GeoShapeCentroidAggregator(name, context, parent, valuesSourceConfig, metadata);
                }
                throw LicenseUtils.newComplianceException("geo_centroid aggregation on geo_shape fields");
            },
            true
        );
    }

    private void registerGeoShapeGridAggregators(ValuesSourceRegistry.Builder builder) {
        builder.register(GeoHashGridAggregationBuilder.REGISTRY_KEY, GeoShapeValuesSourceType.instance(),
            (name, factories, valuesSource, precision, geoBoundingBox, requiredSize, shardSize,
                                         aggregationContext, parent, collectsFromSingleBucket, metadata) -> {
                if (GEO_GRID_AGG_FEATURE.check(getLicenseState())) {
                    final GeoGridTiler tiler;
                    if (geoBoundingBox.isUnbounded()) {
                        tiler = new UnboundedGeoHashGridTiler(precision);
                    } else {
                        tiler = new BoundedGeoHashGridTiler(precision, geoBoundingBox);
                    }
                    GeoShapeCellIdSource cellIdSource = new GeoShapeCellIdSource((GeoShapeValuesSource) valuesSource, tiler);
                    GeoShapeHashGridAggregator agg = new GeoShapeHashGridAggregator(name, factories, cellIdSource, requiredSize, shardSize,
                        aggregationContext, parent, collectsFromSingleBucket, metadata);
                    // this would ideally be something set in an immutable way on the ValuesSource
                    cellIdSource.setCircuitBreakerConsumer(agg::addRequestBytes);
                    return agg;
                }
                throw LicenseUtils.newComplianceException("geohash_grid aggregation on geo_shape fields");
            },
            true
        );

        builder.register(GeoTileGridAggregationBuilder.REGISTRY_KEY, GeoShapeValuesSourceType.instance(),
            (name, factories, valuesSource, precision, geoBoundingBox, requiredSize, shardSize,
                                        context, parent, collectsFromSingleBucket, metadata) -> {
                if (GEO_GRID_AGG_FEATURE.check(getLicenseState())) {
                    final GeoGridTiler tiler;
                    if (geoBoundingBox.isUnbounded()) {
                        tiler = new UnboundedGeoTileGridTiler(precision);
                    } else {
                        tiler = new BoundedGeoTileGridTiler(precision, geoBoundingBox);
                    }
                    GeoShapeCellIdSource cellIdSource = new GeoShapeCellIdSource((GeoShapeValuesSource) valuesSource, tiler);
                    GeoShapeTileGridAggregator agg = new GeoShapeTileGridAggregator(name, factories, cellIdSource, requiredSize, shardSize,
                        context, parent, collectsFromSingleBucket, metadata);
                    // this would ideally be something set in an immutable way on the ValuesSource
                    cellIdSource.setCircuitBreakerConsumer(agg::addRequestBytes);
                    return agg;
                }
                throw LicenseUtils.newComplianceException("geotile_grid aggregation on geo_shape fields");
            },
            true
        );
    }

    private static void registerValueCountAggregator(ValuesSourceRegistry.Builder builder) {
        builder.register(ValueCountAggregationBuilder.REGISTRY_KEY, GeoShapeValuesSourceType.instance(), ValueCountAggregator::new, true);
    }

    private static void registerCardinalityAggregator(ValuesSourceRegistry.Builder builder) {
        builder.register(CardinalityAggregationBuilder.REGISTRY_KEY, GeoShapeValuesSourceType.instance(), CardinalityAggregator::new, true);
    }

    private <T> ContextParser<String, T> checkLicense(ContextParser<String, T> realParser, LicensedFeature.Momentary feature) {
        return (parser, name) -> {
            if (feature.check(getLicenseState()) == false) {
                throw LicenseUtils.newComplianceException(feature.getName());
            }
            return realParser.parse(parser, name);
        };
    }

    @Override
    public void loadExtensions(ExtensionLoader loader) {
        // we only expect one vector tile extension that comes from the vector tile module.
        List<GeoFormatterFactory.FormatterFactory<Geometry>> formatterFactories = new ArrayList<>();
        loader.loadExtensions(GeometryFormatterExtension.class).stream().map(GeometryFormatterExtension::getGeometryFormatterFactories)
            .forEach(formatterFactories::addAll);
        geoFormatterFactory.set(new GeoFormatterFactory<>(formatterFactories));
    }
}
