package org.redisson.hibernate;

import org.hibernate.Session;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.query.Query;
import org.hibernate.stat.Statistics;
import org.hibernate.testing.orm.junit.BaseSessionFactoryFunctionalTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 
 * @author Nikita Koksharov
 *
 */
@Testcontainers
public class ReadWriteTest extends BaseSessionFactoryFunctionalTest {

    @Container
    public static final GenericContainer H2 = new FixedHostPortGenericContainer("oscarfonts/h2:latest")
            .withFixedExposedPort(1521, 1521);

    @Container
    public static final GenericContainer REDIS = new FixedHostPortGenericContainer("redis:latest")
                                                .withFixedExposedPort(6379, 6379);

    @Override
    protected Class<?>[] getAnnotatedClasses() {
        return new Class[] { ItemReadWrite.class};
    }

    @Override
    protected void applySettings(StandardServiceRegistryBuilder builder) {
        builder.applySetting("hibernate.cache.redisson.item.eviction.max_entries", "100");
        builder.applySetting("hibernate.cache.redisson.item.expiration.time_to_live", "1500");
        builder.applySetting("hibernate.cache.redisson.item.expiration.max_idle_time", "1000");
    }
    
    @BeforeEach
    public void before() {
        sessionFactory().getCache().evictAllRegions();
        sessionFactory().getStatistics().clear();
    }

    @Test
    public void testTimeToLive() throws InterruptedException {
        Statistics stats = sessionFactory().getStatistics();

        Long id;
        Session s = sessionFactory().openSession();
        s.beginTransaction();
        ItemReadWrite item = new ItemReadWrite( "data" );
        id = (Long) s.save( item );
        s.flush();
        s.getTransaction().commit();
        s.close();

        Thread.sleep(900);

        s = sessionFactory().openSession();
        s.beginTransaction();
        item = s.get(ItemReadWrite.class, id);
        Assertions.assertEquals("data", item.getName());
        s.getTransaction().commit();
        s.close();

        Assertions.assertEquals(1, stats.getDomainDataRegionStatistics("item").getHitCount());
        Assertions.assertEquals(0, stats.getDomainDataRegionStatistics("item").getMissCount());

        Thread.sleep(600);

        s = sessionFactory().openSession();
        s.beginTransaction();
        item = s.get(ItemReadWrite.class, id);
        Assertions.assertEquals("data", item.getName());
        s.delete(item);
        s.getTransaction().commit();
        s.close();

        Assertions.assertEquals(1, stats.getDomainDataRegionStatistics("item").getHitCount());
        Assertions.assertEquals(1, stats.getDomainDataRegionStatistics("item").getMissCount());
    }

    @Test
    public void testQuery() {
        Statistics stats = sessionFactory().getStatistics();

        Session s = sessionFactory().openSession();
        s.beginTransaction();
        ItemReadWrite item = new ItemReadWrite("data");
        item.getEntries().addAll(Arrays.asList("a", "b", "c"));
        s.save(item);
        s.flush();
        s.getTransaction().commit();
        
        s = sessionFactory().openSession();
        s.beginTransaction();
        Query<ItemReadWrite> query = s.getNamedQuery("testQuery");
        query.setCacheable(true);
        query.setCacheRegion("myTestQuery");
        query.setParameter("name", "data");
        item = query.uniqueResult();
        s.getTransaction().commit();
        s.close();
        
        Assertions.assertEquals(1, stats.getDomainDataRegionStatistics("myTestQuery").getPutCount());

        s = sessionFactory().openSession();
        s.beginTransaction();
        Query<ItemReadWrite> query2 = s.getNamedQuery("testQuery");
        query2.setCacheable(true);
        query2.setCacheRegion("myTestQuery");
        query2.setParameter("name", "data");
        item = query2.uniqueResult();
        s.delete(item);
        s.getTransaction().commit();
        s.close();

        Assertions.assertEquals(1, stats.getDomainDataRegionStatistics("myTestQuery").getHitCount());
        
        stats.logSummary();
    }

    @Test
    public void testCollection() {
        Long id = null;

        Statistics stats = sessionFactory().getStatistics();
        Session s = sessionFactory().openSession();
        s.beginTransaction();
        ItemReadWrite item = new ItemReadWrite("data");
        item.getEntries().addAll(Arrays.asList("a", "b", "c"));
        id = (Long) s.save(item);
        s.flush();
        s.getTransaction().commit();

        s = sessionFactory().openSession();
        s.beginTransaction();
        item = s.get(ItemReadWrite.class, id);
        assertThat(item.getEntries()).containsExactly("a", "b", "c");
        s.getTransaction().commit();
        s.close();

        Assertions.assertEquals(1, stats.getDomainDataRegionStatistics("item_entries").getPutCount());

        s = sessionFactory().openSession();
        s.beginTransaction();
        item = s.get(ItemReadWrite.class, id);
        assertThat(item.getEntries()).containsExactly("a", "b", "c");
        s.delete(item);
        s.getTransaction().commit();
        s.close();

        Assertions.assertEquals(1, stats.getDomainDataRegionStatistics("item_entries").getHitCount());
    }

    @Test
    public void testNaturalId() {
        Statistics stats = sessionFactory().getStatistics();
        Session s = sessionFactory().openSession();
        s.beginTransaction();
        ItemReadWrite item = new ItemReadWrite("data");
        item.setNid("123");
        s.save(item);
        s.flush();
        s.getTransaction().commit();

        Assertions.assertEquals(1, stats.getDomainDataRegionStatistics("item").getPutCount());
        Assertions.assertEquals(1, stats.getNaturalIdStatistics(ItemReadWrite.class.getName()).getCachePutCount());

        s = sessionFactory().openSession();
        s.beginTransaction();
        item = (ItemReadWrite) s.bySimpleNaturalId(ItemReadWrite.class).load("123");
        assertThat(item).isNotNull();
        s.delete(item);
        s.getTransaction().commit();
        s.close();

        Assertions.assertEquals(1, stats.getDomainDataRegionStatistics("item").getHitCount());
        Assertions.assertEquals(1, stats.getNaturalIdStatistics(ItemReadWrite.class.getName()).getCacheHitCount());

        sessionFactory().getStatistics().logSummary();
    }

    @Test
    public void testUpdateWithRefreshThenRollback() {
        Statistics stats = sessionFactory().getStatistics();
        Long id = null;
        Session s = sessionFactory().openSession();
        s.beginTransaction();
        ItemReadWrite item = new ItemReadWrite( "data" );
        id = (Long) s.save( item );
        s.flush();
        s.getTransaction().commit();

        Assertions.assertEquals(1, stats.getDomainDataRegionStatistics("item").getPutCount());

        s = sessionFactory().openSession();
        s.beginTransaction();
        item = s.get(ItemReadWrite.class, id);
        item.setName("newdata");
        s.update(item);
        s.flush();
        s.refresh(item);
        s.getTransaction().rollback();
        s.clear();
        s.close();

        s = sessionFactory().openSession();
        s.beginTransaction();
        item = s.get(ItemReadWrite.class, id);
        Assertions.assertEquals("data", item.getName());
        s.delete(item);
        s.getTransaction().commit();
        s.close();

        Assertions.assertEquals(1, stats.getDomainDataRegionStatistics("item").getHitCount());
    }
        
}
