package org.molgenis.data.elasticsearch;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.elasticsearch.action.ListenableActionFuture;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.molgenis.MolgenisFieldTypes;
import org.molgenis.data.Entity;
import org.molgenis.data.EntityManager;
import org.molgenis.data.EntityManagerImpl;
import org.molgenis.data.EntityMetaData;
import org.molgenis.data.Query;
import org.molgenis.data.Repository;
import org.molgenis.data.elasticsearch.ElasticsearchService.BulkProcessorFactory;
import org.molgenis.data.elasticsearch.ElasticsearchService.IndexingMode;
import org.molgenis.data.elasticsearch.index.EntityToSourceConverter;
import org.molgenis.data.elasticsearch.index.SourceToEntityConverter;
import org.molgenis.data.support.DataServiceImpl;
import org.molgenis.data.support.DefaultEntityMetaData;
import org.molgenis.data.support.MapEntity;
import org.molgenis.data.support.NonDecoratingRepositoryDecoratorFactory;
import org.molgenis.data.support.QueryImpl;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;

public class ElasticsearchServiceTest
{
	private Client client;
	private ElasticsearchService searchService;
	private String indexName;
	private DataServiceImpl dataService;
	private EntityManager entityManager;
	private ElasticsearchEntityFactory elasticsearchEntityFactory;

	@BeforeMethod
	public void beforeMethod() throws InterruptedException
	{
		indexName = "molgenis";
		client = mock(Client.class);

		dataService = spy(new DataServiceImpl(new NonDecoratingRepositoryDecoratorFactory()));
		entityManager = new EntityManagerImpl(dataService);
		SourceToEntityConverter sourceToEntityManager = new SourceToEntityConverter(dataService, entityManager);
		EntityToSourceConverter entityToSourceManager = mock(EntityToSourceConverter.class);
		elasticsearchEntityFactory = new ElasticsearchEntityFactory(entityManager, sourceToEntityManager,
				entityToSourceManager);
		searchService = spy(
				new ElasticsearchService(client, indexName, dataService, elasticsearchEntityFactory, false));
		BulkProcessorFactory bulkProcessorFactory = mock(BulkProcessorFactory.class);
		BulkProcessor bulkProcessor = mock(BulkProcessor.class);
		when(bulkProcessor.awaitClose(any(Long.class), any(TimeUnit.class))).thenReturn(true);
		when(bulkProcessorFactory.create(client)).thenReturn(bulkProcessor);
		ElasticsearchService.setBulkProcessorFactory(bulkProcessorFactory);
		doNothing().when(searchService).refresh(any(String.class));
	}

	@BeforeClass
	public void beforeClass()
	{
	}

	@AfterClass
	public void afterClass()
	{
	}

	@Test
	public void indexEntityAdd()
	{
		DefaultEntityMetaData entityMetaData = createEntityMeta("entity");
		MapEntity entity = createEntityAndRegisterSource(entityMetaData, "id0");

		searchService.index(entity, entityMetaData, IndexingMode.ADD);
		verify(searchService, times(1)).index(indexName, Arrays.asList(entity), entityMetaData,
				ElasticsearchService.CrudType.ADD, true);
	}

	@Test
	public void indexEntityUpdateNoRefs()
	{
		DefaultEntityMetaData entityMetaData = createEntityMeta("entity");
		MapEntity entity = createEntityAndRegisterSource(entityMetaData, "id0");
		when(dataService.getEntityNames()).thenReturn(Lists.newArrayList());

		searchService.index(entity, entityMetaData, IndexingMode.UPDATE);
		verify(searchService, times(1)).index(indexName, Arrays.asList(entity), entityMetaData,
				ElasticsearchService.CrudType.UPDATE, true);
	}

	@SuppressWarnings("unchecked")
	@Test
	public void search()
	{
		int batchSize = 1000;
		int totalSize = batchSize + 1;
		SearchRequestBuilder searchRequestBuilder = mock(SearchRequestBuilder.class);
		String idAttrName = "id";

		ListenableActionFuture<SearchResponse> value1 = mock(ListenableActionFuture.class);
		SearchResponse searchResponse1 = mock(SearchResponse.class);

		SearchHit[] hits1 = new SearchHit[batchSize];
		for (int i = 0; i < batchSize; ++i)
		{
			SearchHit searchHit = mock(SearchHit.class);
			when(searchHit.getSource()).thenReturn(Collections.<String, Object> singletonMap(idAttrName, i + 1));
			when(searchHit.getId()).thenReturn(String.valueOf(i + 1));
			hits1[i] = searchHit;
		}
		SearchHits searchHits1 = createSearchHits(hits1, totalSize);
		when(searchResponse1.getHits()).thenReturn(searchHits1);
		when(value1.actionGet()).thenReturn(searchResponse1);

		ListenableActionFuture<SearchResponse> value2 = mock(ListenableActionFuture.class);
		SearchResponse searchResponse2 = mock(SearchResponse.class);

		SearchHit[] hits2 = new SearchHit[totalSize - batchSize];
		SearchHit searchHit = mock(SearchHit.class);
		when(searchHit.getSource()).thenReturn(Collections.<String, Object> singletonMap(idAttrName, batchSize + 1));
		when(searchHit.getId()).thenReturn(String.valueOf(batchSize + 1));
		hits2[0] = searchHit;
		SearchHits searchHits2 = createSearchHits(hits2, totalSize);
		when(searchResponse2.getHits()).thenReturn(searchHits2);
		when(value2.actionGet()).thenReturn(searchResponse2);

		when(searchRequestBuilder.execute()).thenReturn(value1, value2);
		when(client.prepareSearch(indexName)).thenReturn(searchRequestBuilder);

		Repository repo = when(mock(Repository.class).getName()).thenReturn("entity").getMock();
		List<Object> idsBatch0 = new ArrayList<>();
		for (int i = 0; i < batchSize; ++i)
		{
			idsBatch0.add(i + 1);
		}
		List<Object> idsBatch1 = new ArrayList<>();
		for (int i = batchSize; i < totalSize; ++i)
		{
			idsBatch1.add(i + 1);
		}
		List<Entity> entitiesBatch0 = new ArrayList<>();
		for (int i = 0; i < batchSize; ++i)
		{
			entitiesBatch0.add(when(mock(Entity.class).getIdValue()).thenReturn(i + 1).getMock());
		}
		List<Entity> entitiesBatch1 = new ArrayList<>();
		for (int i = batchSize; i < totalSize; ++i)
		{
			entitiesBatch1.add(when(mock(Entity.class).getIdValue()).thenReturn(i + 1).getMock());
		}
		when(repo.findAll(idsBatch0)).thenReturn(entitiesBatch0);
		when(repo.findAll(idsBatch1)).thenReturn(entitiesBatch1);
		dataService.addRepository(repo);
		DefaultEntityMetaData entityMetaData = new DefaultEntityMetaData("entity");
		entityMetaData.setBackend(ElasticsearchRepositoryCollection.NAME);
		entityMetaData.addAttribute(idAttrName).setDataType(MolgenisFieldTypes.INT).setIdAttribute(true);
		Query q = new QueryImpl();
		Iterable<Entity> searchResults = searchService.search(q, entityMetaData);
		Iterator<Entity> it = searchResults.iterator();
		for (int i = 1; i <= totalSize; ++i)
		{
			assertEquals(it.next().getIdValue(), i);
		}
		assertFalse(it.hasNext());
	}

	private MapEntity createEntityAndRegisterSource(final EntityMetaData metaData, final String id)
	{
		final String idAttributeName = metaData.getIdAttribute().getName();
		final String entityName = metaData.getName();
		MapEntity entity = new MapEntity(idAttributeName);
		entity.set(idAttributeName, id);
		Map<String, Object> source = getSource(metaData, entity);
		whenIndexEntity(client, id, entityName, source);
		return entity;
	}

	private Map<String, Object> getSource(EntityMetaData metaData, Entity entity)
	{
		final String idAttributeName = metaData.getIdAttribute().getName();
		final String entityName = metaData.getName();
		Map<String, Object> source = new HashMap<String, Object>();
		source.put(idAttributeName, entity.get(idAttributeName));
		source.put("type", entityName);
		return source;
	}

	private DefaultEntityMetaData createEntityMeta(String refEntityName)
	{
		DefaultEntityMetaData refEntityMetaData = new DefaultEntityMetaData(refEntityName);
		refEntityMetaData.addAttribute("id").setIdAttribute(true).setUnique(true);
		return refEntityMetaData;
	}

	private void whenIndexEntity(Client client, String id, String entityName, Map<String, Object> source)
	{
		IndexRequestBuilder indexRequestBuilder = mock(IndexRequestBuilder.class);
		when(indexRequestBuilder.setSource(eq(source))).thenReturn(indexRequestBuilder);
		@SuppressWarnings("unchecked")
		ListenableActionFuture<IndexResponse> indexResponse = mock(ListenableActionFuture.class);
		when(indexRequestBuilder.execute()).thenReturn(indexResponse);
		when(client.prepareIndex(indexName, entityName, id)).thenReturn(indexRequestBuilder);
	}

	private SearchHits createSearchHits(final SearchHit[] searchHits, final int totalHits)
	{
		return new SearchHits()
		{
			@Override
			public Iterator<SearchHit> iterator()
			{
				return Arrays.asList(searchHits).iterator();
			}

			@Override
			public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException
			{
				throw new UnsupportedOperationException();
			}

			@Override
			public void writeTo(StreamOutput out) throws IOException
			{
				throw new UnsupportedOperationException();
			}

			@Override
			public void readFrom(StreamInput in) throws IOException
			{
				throw new UnsupportedOperationException();
			}

			@Override
			public long totalHits()
			{
				return getTotalHits();
			}

			@Override
			public float maxScore()
			{
				return getMaxScore();
			}

			@Override
			public SearchHit[] hits()
			{
				return getHits();
			}

			@Override
			public long getTotalHits()
			{
				return totalHits;
			}

			@Override
			public float getMaxScore()
			{
				throw new UnsupportedOperationException();
			}

			@Override
			public SearchHit[] getHits()
			{
				return searchHits;
			}

			@Override
			public SearchHit getAt(int position)
			{
				throw new UnsupportedOperationException();
			}
		};
	}
}
