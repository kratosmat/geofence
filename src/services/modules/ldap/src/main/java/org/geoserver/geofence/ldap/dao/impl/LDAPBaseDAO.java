/* (c) 2014 - 2017 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.geofence.ldap.dao.impl;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.geoserver.geofence.core.dao.RestrictedGenericDAO;
import org.geoserver.geofence.ldap.utils.LdapUtils;

import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;

import com.googlecode.genericdao.search.Filter;
import com.googlecode.genericdao.search.ISearch;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.InitializingBean;
import javax.naming.directory.SearchControls;

/**
 * Base DAO Implementation using LDAP services.
 *
 * It uses a spring-ldap LdapTemplate to communicate with the LDAP server. 
 * 
 * Currently only read type operations are supported (findAll, find, search).
 *
 * Search results are cached in order to avoid too many calls to the LDAP services.
 *
 * @author "Mauro Bartolomeoli - mauro.bartolomeoli@geo-solutions.it"
 * @author Emanuele Tajariol (etj at geo-solutions.it)
 */
public abstract class LDAPBaseDAO<T extends RestrictedGenericDAO<R>, R> 
            implements RestrictedGenericDAO<R>, InitializingBean
{
    protected Logger LOGGER = LogManager.getLogger(getClass());

    private LdapTemplate ldapTemplate;
    private String searchBase;
    private String searchFilter;
    private AttributesMapper attributesMapper;

    private LoadingCache<String, List<R>> ldapcache;
    private long cachesize = 1000;
    private long cacherefreshsec = 60 * 60; // 1 hour
    private long cacheexpiresec = 60 * 60; // 1 hour
    private final AtomicLong dumpCnt = new AtomicLong(0);
    private long cachedumpmodulo = 10;

    public LDAPBaseDAO()
    {
    }

    @Override
    public void afterPropertiesSet() throws Exception
    {
        ldapcache  = getCacheBuilder().build(new LDAPLoader());
    }

    protected CacheBuilder getCacheBuilder() {
        CacheBuilder builder = CacheBuilder.newBuilder()
                .maximumSize(cachesize)
                .refreshAfterWrite(cacherefreshsec, TimeUnit.SECONDS) // reloadable after x time
                .expireAfterWrite(cacheexpiresec, TimeUnit.SECONDS) // throw away entries too old
                .recordStats()
                ;
        return builder;
    }


    protected String getLDAPAttribute(String attrName) {
        return ((BaseAttributesMapper)attributesMapper).getLdapAttribute(attrName);
    }

    @Override
    public List<R> findAll()
    {
        List ret = search(searchFilter);
        if(LOGGER.isDebugEnabled()) {
            LOGGER.debug("findAll returned " + ret.size() + " items");
        }
        return ret;
    }

    @Override
    public R find(Long id)
    {
        LOGGER.warn(getClass().getSimpleName() + ": search by id is deprecated (id="+id+")");
        return null;
    }

    @Override
    public List<R> search(ISearch search)
    {
        List<R> objects = new ArrayList<>();
        if (search.getFilters().isEmpty()) {
            // no filter
            return findAll();
        }
        for (Filter filter : search.getFilters()) {
            if (filter != null) {
                List<R> filteredObjects = search(filter);
                objects.addAll(filteredObjects);
            }
        }
        return objects;
    }

    @Override
    public int count(ISearch search)
    {
        return search(search).size();
    }

    @Override
    public void persist(R... entities)
    {
        LOGGER.warn(getClass().getSimpleName() + ": persisting not allowed in LDAP");
    }

    @Override
    public R merge(R entity)
    {
        LOGGER.warn(getClass().getSimpleName() + ": persisting not allowed in LDAP");
        return entity;
    }

    @Override
    public boolean remove(R entity)
    {
        LOGGER.warn(getClass().getSimpleName() + ": persisting not allowed in LDAP");
        return false;
    }

    @Override
    public boolean removeById(Long id)
    {
        LOGGER.warn(getClass().getSimpleName() + ": persisting not allowed in LDAP");
        return false;
    }

    /**
     * Does a direct lookup for the distinguished name given.
     *
     * @param dn distinguished name to lookup
     * @return
     */
    public R lookup(String dn)
    {
        return (R) ldapTemplate.lookup(dn, attributesMapper);
    }

    /**
     * Search using the given filter on the LDAP server. Uses default base, filter and mapper.
     *
     * @param base
     * @param filter
     * @param mapper
     * @return
     */
    public List search(Filter filter)
    {
        return search(LdapUtils.createLDAPFilter(filter, attributesMapper));
    }

    /**
     * Search using the given filter on the LDAP server. Uses default base, filter and mapper.
     *
     * @param base
     * @param filter
     * @param mapper
     * @return
     */
    public List search(String filter)
    {
        if(LOGGER.isTraceEnabled())
            LOGGER.trace(getClass().getSimpleName() + ": searching base:'"+searchBase+"', filter: '"+filter+"'");

        if(LOGGER.isInfoEnabled()) {
            if(dumpCnt.incrementAndGet() % cachedumpmodulo == 0) {
                LOGGER.info("LDAP Cache  :"+ ldapcache.stats());
            }
        }

        try {
            return ldapcache.get(filter);
            //return search(ldapTemplate, searchBase, filter, attributesMapper);
        } catch (ExecutionException ex) {
            LOGGER.warn("Error while getting LDAP info: " + ex.getMessage(), ex);
            return Collections.EMPTY_LIST;
        }
    }

    private class LDAPLoader extends CacheLoader<String, List<R>> {

        private String[] getAttributes() {
            String[] attrs = null;
            if(attributesMapper instanceof GSUserAttributesMapper) {
                int size = ((GSUserAttributesMapper)attributesMapper).getMap().size();
                attrs = ((GSUserAttributesMapper)attributesMapper).getMap().values().toArray(new String[size]);
            }
            return attrs;
        }

        @Override
        public List<R> load(String filter) throws Exception {
            if(LOGGER.isInfoEnabled())
                LOGGER.info("Loading " + filter);

            String[] attrs = getAttributes();

            return ldapTemplate.search(searchBase, filter, SearchControls.SUBTREE_SCOPE, attrs, attributesMapper);
        }

        @Override
        public ListenableFuture<List<R>> reload(final String filter, List<R> accessInfo) throws Exception
        {
            if(LOGGER.isInfoEnabled())
                LOGGER.info("RELoading " + filter);

            // this is a sync implementation
            String[] attrs = getAttributes();

            List<R> ldapObjs =  ldapTemplate.search(searchBase, filter, SearchControls.SUBTREE_SCOPE, attrs, attributesMapper);

            //List<R> ldapObjs = ldapTemplate.search(searchBase, filter, attributesMapper);
            return Futures.immediateFuture(ldapObjs);
        }
    }

    /**
     * Sets the base name for users in LDAP server.
     *
     * @param searchBase the searchBase to set
     */
    public void setSearchBase(String searchBase)
    {
        this.searchBase = searchBase;
    }

    /**
     * Sets the filter used to identify objects from the base name.
     *
     * @param searchFilter the searchFilter to set
     */
    public void setSearchFilter(String searchFilter)
    {
        this.searchFilter = searchFilter;
    }

    /**
     * Sets the AttributeMapper used to build objects from LDAP objects.
     *
     * @param attributesMapper the attributesMapper to set
     */
    public void setAttributesMapper(AttributesMapper attributesMapper)
    {
        this.attributesMapper = attributesMapper;
    }

    /**
     * Sets the LDAP communication object.
     *
     * @param ldapTemplate the ldapTemplate to set
     */
    public void setLdapTemplate(LdapTemplate ldapTemplate)
    {
        this.ldapTemplate = ldapTemplate;
    }

    public void setCachesize(long cachesize)
    {
        this.cachesize = cachesize;
    }

    public void setCacherefreshsec(long cacherefreshsec)
    {
        this.cacherefreshsec = cacherefreshsec;
    }

    public void setCacheexpiresec(long cacheexpiresec)
    {
        this.cacheexpiresec = cacheexpiresec;
    }

    public void setCachedumpmodulo(long cachedumpmodulo)
    {
        this.cachedumpmodulo = cachedumpmodulo;
    }

}
