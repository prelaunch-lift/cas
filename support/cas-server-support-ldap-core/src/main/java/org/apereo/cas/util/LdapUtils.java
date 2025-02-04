package org.apereo.cas.util;

import org.apereo.cas.authentication.AuthenticationPasswordPolicyHandlingStrategy;
import org.apereo.cas.authentication.CoreAuthenticationUtils;
import org.apereo.cas.authentication.LdapAuthenticationHandler;
import org.apereo.cas.authentication.principal.PrincipalFactory;
import org.apereo.cas.authentication.principal.PrincipalNameTransformerUtils;
import org.apereo.cas.authentication.support.DefaultLdapAccountStateHandler;
import org.apereo.cas.authentication.support.OptionalWarningLdapAccountStateHandler;
import org.apereo.cas.authentication.support.RejectResultCodeLdapPasswordPolicyHandlingStrategy;
import org.apereo.cas.authentication.support.password.DefaultPasswordPolicyHandlingStrategy;
import org.apereo.cas.authentication.support.password.GroovyPasswordPolicyHandlingStrategy;
import org.apereo.cas.authentication.support.password.PasswordEncoderUtils;
import org.apereo.cas.authentication.support.password.PasswordPolicyContext;
import org.apereo.cas.configuration.model.support.ldap.AbstractLdapAuthenticationProperties;
import org.apereo.cas.configuration.model.support.ldap.AbstractLdapProperties;
import org.apereo.cas.configuration.model.support.ldap.LdapAuthenticationProperties;
import org.apereo.cas.configuration.model.support.ldap.LdapPasswordPolicyProperties;
import org.apereo.cas.configuration.model.support.ldap.LdapSearchEntryHandlersProperties;
import org.apereo.cas.configuration.support.Beans;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.util.function.FunctionUtils;
import org.apereo.cas.util.scripting.ExecutableCompiledGroovyScript;
import org.apereo.cas.util.scripting.ScriptResourceCacheManager;
import org.apereo.cas.util.scripting.WatchableGroovyScriptResource;
import org.apereo.cas.util.spring.ApplicationContextProvider;

import com.google.common.collect.Multimap;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apereo.services.persondir.support.ldap.ActiveDirectoryLdapEntryHandler;
import org.jooq.lambda.Unchecked;
import org.ldaptive.ActivePassiveConnectionStrategy;
import org.ldaptive.AddOperation;
import org.ldaptive.AddRequest;
import org.ldaptive.AttributeModification;
import org.ldaptive.BindConnectionInitializer;
import org.ldaptive.CompareConnectionValidator;
import org.ldaptive.CompareRequest;
import org.ldaptive.ConnectionConfig;
import org.ldaptive.ConnectionFactory;
import org.ldaptive.Credential;
import org.ldaptive.DefaultConnectionFactory;
import org.ldaptive.DeleteOperation;
import org.ldaptive.DeleteRequest;
import org.ldaptive.DerefAliases;
import org.ldaptive.DnsSrvConnectionStrategy;
import org.ldaptive.FilterTemplate;
import org.ldaptive.LdapAttribute;
import org.ldaptive.LdapEntry;
import org.ldaptive.LdapException;
import org.ldaptive.ModifyOperation;
import org.ldaptive.ModifyRequest;
import org.ldaptive.PooledConnectionFactory;
import org.ldaptive.RandomConnectionStrategy;
import org.ldaptive.ResultCode;
import org.ldaptive.ReturnAttributes;
import org.ldaptive.RoundRobinConnectionStrategy;
import org.ldaptive.SearchConnectionValidator;
import org.ldaptive.SearchOperation;
import org.ldaptive.SearchRequest;
import org.ldaptive.SearchResponse;
import org.ldaptive.SearchScope;
import org.ldaptive.SimpleBindRequest;
import org.ldaptive.ad.UnicodePwdAttribute;
import org.ldaptive.ad.extended.FastBindConnectionInitializer;
import org.ldaptive.ad.handler.ObjectGuidHandler;
import org.ldaptive.ad.handler.ObjectSidHandler;
import org.ldaptive.ad.handler.PrimaryGroupIdHandler;
import org.ldaptive.ad.handler.RangeEntryHandler;
import org.ldaptive.auth.AuthenticationCriteria;
import org.ldaptive.auth.AuthenticationHandlerResponse;
import org.ldaptive.auth.AuthenticationRequestHandler;
import org.ldaptive.auth.AuthenticationResponse;
import org.ldaptive.auth.AuthenticationResponseHandler;
import org.ldaptive.auth.Authenticator;
import org.ldaptive.auth.CompareAuthenticationHandler;
import org.ldaptive.auth.DnResolver;
import org.ldaptive.auth.EntryResolver;
import org.ldaptive.auth.FormatDnResolver;
import org.ldaptive.auth.SearchDnResolver;
import org.ldaptive.auth.SearchEntryResolver;
import org.ldaptive.auth.SimpleBindAuthenticationHandler;
import org.ldaptive.auth.User;
import org.ldaptive.auth.ext.ActiveDirectoryAuthenticationResponseHandler;
import org.ldaptive.auth.ext.EDirectoryAuthenticationResponseHandler;
import org.ldaptive.auth.ext.FreeIPAAuthenticationResponseHandler;
import org.ldaptive.auth.ext.PasswordExpirationAuthenticationResponseHandler;
import org.ldaptive.auth.ext.PasswordPolicyAuthenticationRequestHandler;
import org.ldaptive.auth.ext.PasswordPolicyAuthenticationResponseHandler;
import org.ldaptive.control.util.PagedResultsClient;
import org.ldaptive.extended.ExtendedOperation;
import org.ldaptive.extended.PasswordModifyRequest;
import org.ldaptive.handler.CaseChangeEntryHandler;
import org.ldaptive.handler.DnAttributeEntryHandler;
import org.ldaptive.handler.LdapEntryHandler;
import org.ldaptive.handler.MergeAttributeEntryHandler;
import org.ldaptive.handler.MergeResultHandler;
import org.ldaptive.handler.RecursiveResultHandler;
import org.ldaptive.handler.SearchResultHandler;
import org.ldaptive.pool.BindConnectionPassivator;
import org.ldaptive.pool.IdlePruneStrategy;
import org.ldaptive.referral.FollowSearchReferralHandler;
import org.ldaptive.sasl.Mechanism;
import org.ldaptive.sasl.QualityOfProtection;
import org.ldaptive.sasl.SaslConfig;
import org.ldaptive.sasl.SecurityStrength;
import org.ldaptive.ssl.AllowAnyHostnameVerifier;
import org.ldaptive.ssl.AllowAnyTrustManager;
import org.ldaptive.ssl.DefaultHostnameVerifier;
import org.ldaptive.ssl.DefaultTrustManager;
import org.ldaptive.ssl.KeyStoreCredentialConfig;
import org.ldaptive.ssl.SslConfig;
import org.ldaptive.ssl.X509CredentialConfig;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.SetFactoryBean;
import org.springframework.context.ApplicationContext;

import javax.security.auth.login.AccountNotFoundException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Period;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Utilities related to LDAP functions.
 *
 * @author Scott Battaglia
 * @author Misagh Moayyed
 * @since 3.0.0
 */
@Slf4j
@UtilityClass
public class LdapUtils {
    /**
     * Default parameter name in search filters for ldap.
     */
    public static final String LDAP_SEARCH_FILTER_DEFAULT_PARAM_NAME = "user";

    /**
     * The objectClass attribute.
     */
    public static final String OBJECT_CLASS_ATTRIBUTE = "objectClass";

    /**
     * Delimiter character to separate multiple base-dns
     * that belongs to the same LDAP instance.
     */
    private static final String BASE_DN_DELIMITER = "|";

    private static final String LDAP_PREFIX = "ldap";

    /**
     * Reads a Boolean value from the LdapEntry.
     *
     * @param ctx       the ldap entry
     * @param attribute the attribute name
     * @param nullValue the value which should be returning in case of a null value
     * @return {@code true} if the attribute's value matches (case-insensitive) {@code "true"}, otherwise false
     */
    public static Boolean getBoolean(final LdapEntry ctx, final String attribute, final Boolean nullValue) {
        val v = getString(ctx, attribute, nullValue.toString());
        return v.equalsIgnoreCase(Boolean.TRUE.toString());
    }

    /**
     * Reads a Long value from the LdapEntry.
     *
     * @param entry     the ldap entry
     * @param attribute the attribute name
     * @param nullValue the value which should be returning in case of a null value
     * @return the long value
     */
    public static Long getLong(final LdapEntry entry, final String attribute, final Long nullValue) {
        val v = getString(entry, attribute, nullValue.toString());
        return Long.valueOf(v);
    }

    /**
     * Reads a String value from the LdapEntry.
     *
     * @param entry     the ldap entry
     * @param attribute the attribute name
     * @return the string
     */
    public static String getString(final LdapEntry entry, final String attribute) {
        return getString(entry, attribute, null);
    }

    /**
     * Reads a String value from the LdapEntry.
     *
     * @param entry     the ldap entry
     * @param attribute the attribute name
     * @param nullValue the value which should be returning in case of a null value
     * @return the string
     */
    public static String getString(final LdapEntry entry, final String attribute, final String nullValue) {
        val attr = entry.getAttribute(attribute);
        if (attr == null) {
            return nullValue;
        }

        val v = attr.isBinary()
            ? new String(attr.getBinaryValue(), StandardCharsets.UTF_8)
            : attr.getStringValue();

        if (StringUtils.isNotBlank(v)) {
            return v;
        }
        return nullValue;
    }

    /**
     * Execute search operation.
     *
     * @param connectionFactory the connection factory
     * @param baseDn            the base dn
     * @param filter            the filter
     * @param pageSize          the page size
     * @param returnAttributes  the return attributes
     * @return the response
     * @throws LdapException the ldap exception
     */
    public static SearchResponse executeSearchOperation(final ConnectionFactory connectionFactory,
                                                        final String baseDn,
                                                        final FilterTemplate filter,
                                                        final int pageSize,
                                                        final String... returnAttributes) throws LdapException {
        return executeSearchOperation(connectionFactory, baseDn,
            filter, pageSize, null, returnAttributes);
    }

    /**
     * Execute search operation.
     *
     * @param connectionFactory the connection factory
     * @param baseDn            the base dn
     * @param filter            the filter
     * @param pageSize          the page size
     * @param binaryAttributes  the binary attributes
     * @param returnAttributes  the return attributes
     * @return the response
     * @throws LdapException the ldap exception
     */
    public static SearchResponse executeSearchOperation(final ConnectionFactory connectionFactory,
                                                        final String baseDn,
                                                        final FilterTemplate filter,
                                                        final int pageSize,
                                                        final String[] binaryAttributes,
                                                        final String[] returnAttributes) throws LdapException {
        val request = LdapUtils.newLdaptiveSearchRequest(baseDn, filter, binaryAttributes, returnAttributes);
        if (pageSize <= 0) {
            val searchOperation = new SearchOperation(connectionFactory);
            searchOperation.setSearchResultHandlers(new FollowSearchReferralHandler());
            return searchOperation.execute(request);
        }
        val client = new PagedResultsClient(connectionFactory, pageSize);
        return client.executeToCompletion(request);
    }

    /**
     * Execute search operation response.
     *
     * @param connectionFactory the connection factory
     * @param baseDn            the base dn
     * @param filter            the filter
     * @param pageSize          the page size
     * @return the response
     * @throws LdapException the ldap exception
     */
    public static SearchResponse executeSearchOperation(final ConnectionFactory connectionFactory,
                                                        final String baseDn,
                                                        final FilterTemplate filter,
                                                        final int pageSize) throws LdapException {
        return executeSearchOperation(connectionFactory, baseDn, filter, pageSize,
            ReturnAttributes.ALL_USER.value(), ReturnAttributes.ALL_USER.value());
    }

    /**
     * Checks to see if response has a result.
     *
     * @param response the response
     * @return true, if successful
     */
    public static boolean containsResultEntry(final SearchResponse response) {
        return response != null && response.getEntry() != null;
    }

    /**
     * Execute a password modify operation.
     *
     * @param currentDn         the current dn
     * @param connectionFactory the connection factory
     * @param oldPassword       the old password
     * @param newPassword       the new password
     * @param type              the type
     * @return true /false
     * <p>
     * AD NOTE: Resetting passwords requires binding to AD as user with privileges to reset other users passwords
     * and it does not validate old password or respect directory policies such as history or minimum password age.
     * Changing a password with the old password does respect directory policies and requires no account operator
     * privileges on the bind user. Pass in blank old password if reset is in order (e.g. forgot password) vs.
     * letting user change their own (e.g. expiring) password.
     */
    public static boolean executePasswordModifyOperation(final String currentDn,
                                                         final ConnectionFactory connectionFactory,
                                                         final String oldPassword,
                                                         final String newPassword,
                                                         final AbstractLdapProperties.LdapType type) {
        try {
            val connConfig = connectionFactory.getConnectionConfig();
            val secureLdap = connConfig.getLdapUrl() != null && !connConfig.getLdapUrl().toLowerCase().contains("ldaps://");
            if (connConfig.getUseStartTLS() || secureLdap) {
                LOGGER.warn("Executing password modification op under a non-secure LDAP connection; "
                        + "To modify password attributes, the connection to the LDAP server {} be secured and/or encrypted.",
                    type == AbstractLdapProperties.LdapType.AD ? "MUST" : "SHOULD");
            }
            if (type == AbstractLdapProperties.LdapType.AD) {
                LOGGER.debug("Executing password change op for active directory based on "
                    + "[https://support.microsoft.com/en-us/kb/269190]"
                    + "change type: [{}]", StringUtils.isBlank(oldPassword) ? "reset" : "change");
                val operation = new ModifyOperation(connectionFactory);
                val response = StringUtils.isBlank(oldPassword)
                    ?
                    operation.execute(new ModifyRequest(currentDn,
                        new AttributeModification(AttributeModification.Type.REPLACE, new UnicodePwdAttribute(newPassword))))
                    :
                    operation.execute(new ModifyRequest(currentDn,
                        new AttributeModification(AttributeModification.Type.DELETE, new UnicodePwdAttribute(oldPassword)),
                        new AttributeModification(AttributeModification.Type.ADD, new UnicodePwdAttribute(newPassword))));
                LOGGER.debug("Result code [{}], message: [{}]", response.getResultCode(), response.getDiagnosticMessage());
                return response.getResultCode() == ResultCode.SUCCESS;
            }

            LOGGER.debug("Executing password modification op for generic LDAP");
            val operation = new ExtendedOperation(connectionFactory);
            val response = operation.execute(new PasswordModifyRequest(currentDn,
                StringUtils.isNotBlank(oldPassword) ? oldPassword : null,
                newPassword));
            LOGGER.debug("Result code [{}], message: [{}]", response.getResultCode(), response.getDiagnosticMessage());
            return response.getResultCode() == ResultCode.SUCCESS;
        } catch (final Exception e) {
            LoggingUtils.error(LOGGER, e);
        }
        return false;
    }

    /**
     * Execute modify operation boolean.
     *
     * @param currentDn         the current dn
     * @param connectionFactory the connection factory
     * @param attributes        the attributes
     * @return true/false
     */
    public static boolean executeModifyOperation(final String currentDn, final ConnectionFactory connectionFactory,
                                                 final Map<String, Set<String>> attributes) {
        try {
            val operation = new ModifyOperation(connectionFactory);
            val mods = attributes.entrySet()
                .stream()
                .map(entry -> {
                    val values = entry.getValue().toArray(ArrayUtils.EMPTY_STRING_ARRAY);
                    val attr = new LdapAttribute(entry.getKey(), values);
                    LOGGER.debug("Constructed new attribute [{}]", attr);
                    return new AttributeModification(AttributeModification.Type.REPLACE, attr);
                })
                .toArray(AttributeModification[]::new);
            val request = new ModifyRequest(currentDn, mods);
            val response = operation.execute(request);
            LOGGER.debug("Result code [{}], message: [{}]", response.getResultCode(), response.getDiagnosticMessage());
            return response.getResultCode() == ResultCode.SUCCESS;
        } catch (final Exception e) {
            LoggingUtils.error(LOGGER, e);
        }
        return false;
    }

    /**
     * Execute modify operation boolean.
     *
     * @param currentDn         the current dn
     * @param connectionFactory the connection factory
     * @param entry             the entry
     * @return true/false
     */
    public static boolean executeModifyOperation(final String currentDn, final ConnectionFactory connectionFactory, final LdapEntry entry) {
        final Map<String, Set<String>> attributes = entry.getAttributes().stream()
            .collect(Collectors.toMap(LdapAttribute::getName, ldapAttribute -> new HashSet<>(ldapAttribute.getStringValues())));

        return executeModifyOperation(currentDn, connectionFactory, attributes);
    }

    /**
     * Execute add operation boolean.
     *
     * @param connectionFactory the connection factory
     * @param entry             the entry
     * @return true/false
     */
    public static boolean executeAddOperation(final ConnectionFactory connectionFactory, final LdapEntry entry) {
        try {
            val operation = new AddOperation(connectionFactory);
            val response = operation.execute(new AddRequest(entry.getDn(), entry.getAttributes()));
            LOGGER.debug("Result code [{}], message: [{}]", response.getResultCode(), response.getDiagnosticMessage());
            return response.getResultCode() == ResultCode.SUCCESS;
        } catch (final Exception e) {
            LoggingUtils.error(LOGGER, e);
        }
        return false;
    }

    /**
     * Execute delete operation boolean.
     *
     * @param connectionFactory the connection factory
     * @param entry             the entry
     * @return true/false
     */
    public static boolean executeDeleteOperation(final ConnectionFactory connectionFactory, final LdapEntry entry) {
        try {
            val delete = new DeleteOperation(connectionFactory);
            val request = new DeleteRequest(entry.getDn());
            val response = delete.execute(request);
            LOGGER.debug("Result code [{}], message: [{}]", response.getResultCode(), response.getDiagnosticMessage());
            return response.getResultCode() == ResultCode.SUCCESS;
        } catch (final Exception e) {
            LoggingUtils.error(LOGGER, e);
        }
        return false;
    }

    /**
     * Is ldap connection url?.
     *
     * @param r the resource
     * @return true/false
     */
    public static boolean isLdapConnectionUrl(final String r) {
        return r.toLowerCase().startsWith(LDAP_PREFIX);
    }

    /**
     * Is ldap connection url?.
     *
     * @param r the resource
     * @return true/false
     */
    public static boolean isLdapConnectionUrl(final URI r) {
        return r.getScheme().equalsIgnoreCase(LDAP_PREFIX);
    }

    /**
     * Is ldap connection url?.
     *
     * @param r the resource
     * @return true/false
     */
    public static boolean isLdapConnectionUrl(final URL r) {
        return r.getProtocol().equalsIgnoreCase(LDAP_PREFIX);
    }

    /**
     * Builds a new request.
     *
     * @param baseDn           the base dn
     * @param filter           the filter
     * @param binaryAttributes the binary attributes
     * @param returnAttributes the return attributes
     * @return the search request
     */
    public static SearchRequest newLdaptiveSearchRequest(final String baseDn,
                                                         final FilterTemplate filter,
                                                         final String[] binaryAttributes,
                                                         final String[] returnAttributes) {
        val sr = new SearchRequest(baseDn, filter);
        sr.setBinaryAttributes(binaryAttributes);
        sr.setReturnAttributes(returnAttributes);
        sr.setSearchScope(SearchScope.SUBTREE);
        return sr;
    }

    /**
     * New ldaptive search executor search executor.
     *
     * @param baseDn           the base dn
     * @param filterQuery      the filter query
     * @param params           the params
     * @param returnAttributes the return attributes
     * @return the search executor
     */
    public static SearchRequest newLdaptiveSearchRequest(final String baseDn, final String filterQuery,
                                                         final List<String> params,
                                                         final String[] returnAttributes) {
        val request = new SearchRequest();
        request.setBaseDn(baseDn);
        request.setFilter(newLdaptiveSearchFilter(filterQuery, params));
        request.setReturnAttributes(returnAttributes);
        request.setSearchScope(SearchScope.SUBTREE);
        return request;
    }

    /**
     * New ldaptive search request.
     * Returns all attributes.
     *
     * @param baseDn the base dn
     * @param filter the filter
     * @return the search request
     */
    public static SearchRequest newLdaptiveSearchRequest(final String baseDn,
                                                         final FilterTemplate filter) {
        return newLdaptiveSearchRequest(baseDn, filter, ReturnAttributes.ALL_USER.value(), ReturnAttributes.ALL_USER.value());
    }

    /**
     * Constructs a new search filter.
     *
     * @param filterQuery the query filter
     * @return Search filter with parameters applied.
     */
    public static FilterTemplate newLdaptiveSearchFilter(final String filterQuery) {
        return newLdaptiveSearchFilter(filterQuery, new ArrayList<>(0));
    }

    /**
     * Constructs a new search filter.
     *
     * @param filterQuery the query filter
     * @param params      the username
     * @return Search filter with parameters applied.
     */
    public static FilterTemplate newLdaptiveSearchFilter(final String filterQuery, final List<String> params) {
        return newLdaptiveSearchFilter(filterQuery, LDAP_SEARCH_FILTER_DEFAULT_PARAM_NAME, params);
    }

    /**
     * Constructs a new search filter.
     *
     * @param filterQuery the query filter
     * @param paramName   the param name
     * @param params      the username
     * @return Search filter with parameters applied.
     */
    public static FilterTemplate newLdaptiveSearchFilter(final String filterQuery, final String paramName, final List<String> params) {
        return newLdaptiveSearchFilter(filterQuery, List.of(paramName), params);
    }

    /**
     * New ldaptive search filter search filter.
     *
     * @param filterQuery the filter query
     * @param paramName   the param name
     * @param values      the params
     * @return the search filter
     */
    public static FilterTemplate newLdaptiveSearchFilter(final String filterQuery,
                                                         final List<String> paramName,
                                                         final List<String> values) {
        val filter = new FilterTemplate();
        if (ResourceUtils.doesResourceExist(filterQuery)) {
            ApplicationContextProvider.getScriptResourceCacheManager().ifPresentOrElse(cacheMgr -> {
                val cacheKey = ScriptResourceCacheManager.computeKey(filterQuery);
                var script = (ExecutableCompiledGroovyScript) null;
                if (cacheMgr.containsKey(cacheKey)) {
                    script = cacheMgr.get(cacheKey);
                    LOGGER.trace("Located cached groovy script [{}] for key [{}]", script, cacheKey);
                } else {
                    val resource = Unchecked.supplier(() -> ResourceUtils.getRawResourceFrom(filterQuery)).get();
                    LOGGER.trace("Groovy script [{}] for key [{}] is not cached", resource, cacheKey);
                    script = new WatchableGroovyScriptResource(resource);
                    cacheMgr.put(cacheKey, script);
                    LOGGER.trace("Cached groovy script [{}] for key [{}]", script, cacheKey);
                }
                if (script != null) {
                    val parameters = new LinkedHashMap<String, String>();
                    IntStream.range(0, values.size())
                        .forEachOrdered(i -> parameters.put(paramName.get(i), values.get(i)));
                    val args = CollectionUtils.<String, Object>wrap("filter", filter,
                        "parameters", parameters,
                        "applicationContext", ApplicationContextProvider.getApplicationContext(),
                        "logger", LOGGER);
                    script.setBinding(args);
                    script.execute(args.values().toArray(), FilterTemplate.class);
                }
            },
                () -> {
                    throw new RuntimeException("Script cache manager unavailable to handle LDAP filter");
                });
        } else {
            filter.setFilter(filterQuery);
            if (values != null) {
                IntStream.range(0, values.size()).forEach(i -> {
                    val value = values.get(i);
                    if (filter.getFilter().contains("{" + i + '}')) {
                        filter.setParameter(i, value);
                    }
                    val name = paramName.get(i);
                    if (filter.getFilter().contains('{' + name + '}')) {
                        filter.setParameter(name, value);
                    }
                });
            }
        }

        LOGGER.debug("Constructed LDAP search filter [{}]", filter.format());
        return filter;
    }

    /**
     * New search executor.
     *
     * @param baseDn      the base dn
     * @param filterQuery the filter query
     * @param params      the params
     * @return the search executor
     */
    public static SearchOperation newLdaptiveSearchOperation(final String baseDn, final String filterQuery, final List<String> params) {
        return newLdaptiveSearchOperation(baseDn, filterQuery, params, List.of(ReturnAttributes.ALL.value()));
    }

    /**
     * New ldaptive search executor search executor.
     *
     * @param baseDn           the base dn
     * @param filterQuery      the filter query
     * @param params           the params
     * @param returnAttributes the return attributes
     * @return the search executor
     */
    public static SearchOperation newLdaptiveSearchOperation(final String baseDn, final String filterQuery,
                                                             final List<String> params,
                                                             final List<String> returnAttributes) {
        val operation = new SearchOperation();
        val request = newLdaptiveSearchRequest(baseDn, filterQuery, params, returnAttributes.toArray(ArrayUtils.EMPTY_STRING_ARRAY));
        operation.setRequest(request);
        operation.setTemplate(newLdaptiveSearchFilter(filterQuery, params));
        return operation;
    }

    /**
     * New search executor search executor.
     *
     * @param baseDn      the base dn
     * @param filterQuery the filter query
     * @return the search executor
     */
    public static SearchOperation newLdaptiveSearchOperation(final String baseDn, final String filterQuery) {
        return newLdaptiveSearchOperation(baseDn, filterQuery, new ArrayList<>(0));
    }

    /**
     * New ldap authenticator.
     *
     * @param l the ldap settings.
     * @return the authenticator
     */
    public static Authenticator newLdaptiveAuthenticator(final AbstractLdapAuthenticationProperties l) {
        switch (l.getType()) {
            case AD:
                LOGGER.debug("Creating active directory authenticator for [{}]", l.getLdapUrl());
                return getActiveDirectoryAuthenticator(l);
            case DIRECT:
                LOGGER.debug("Creating direct-bind authenticator for [{}]", l.getLdapUrl());
                return getDirectBindAuthenticator(l);
            case AUTHENTICATED:
                LOGGER.debug("Creating authenticated authenticator for [{}]", l.getLdapUrl());
                return getAuthenticatedOrAnonSearchAuthenticator(l);
            default:
                LOGGER.debug("Creating anonymous authenticator for [{}]", l.getLdapUrl());
                return getAuthenticatedOrAnonSearchAuthenticator(l);
        }
    }

    /**
     * New pooled connection factory pooled connection factory.
     *
     * @param l the ldap properties
     * @return the pooled connection factory
     */
    public static PooledConnectionFactory newLdaptivePooledConnectionFactory(final AbstractLdapProperties l) {
        val cc = newLdaptiveConnectionConfig(l);

        LOGGER.debug("Creating LDAP connection pool configuration for [{}]", l.getLdapUrl());
        val pooledCf = new PooledConnectionFactory(cc);
        pooledCf.setMinPoolSize(l.getMinPoolSize());
        pooledCf.setMaxPoolSize(l.getMaxPoolSize());
        pooledCf.setValidateOnCheckOut(l.isValidateOnCheckout());
        pooledCf.setValidatePeriodically(l.isValidatePeriodically());
        pooledCf.setBlockWaitTime(Beans.newDuration(l.getBlockWaitTime()));

        val strategy = new IdlePruneStrategy();
        strategy.setIdleTime(Beans.newDuration(l.getIdleTime()));
        strategy.setPrunePeriod(Beans.newDuration(l.getPrunePeriod()));

        pooledCf.setPruneStrategy(strategy);

        val validator = l.getValidator();
        switch (validator.getType().trim().toLowerCase()) {
            case "compare":
                val compareRequest = new CompareRequest(
                    validator.getDn(),
                    validator.getAttributeName(),
                    validator.getAttributeValue());
                val compareValidator = new CompareConnectionValidator(compareRequest);
                compareValidator.setValidatePeriod(Beans.newDuration(l.getValidatePeriod()));
                compareValidator.setValidateTimeout(Beans.newDuration(l.getValidateTimeout()));
                pooledCf.setValidator(compareValidator);
                break;
            case "none":
                LOGGER.debug("No validator is configured for the LDAP connection pool of [{}]", l.getLdapUrl());
                break;
            case "search":
            default:
                val searchRequest = new SearchRequest();
                searchRequest.setBaseDn(validator.getBaseDn());
                searchRequest.setFilter(validator.getSearchFilter());
                searchRequest.setReturnAttributes(ReturnAttributes.NONE.value());
                searchRequest.setSearchScope(SearchScope.valueOf(validator.getScope()));
                searchRequest.setSizeLimit(1);
                val searchValidator = new SearchConnectionValidator(searchRequest);
                searchValidator.setValidatePeriod(Beans.newDuration(l.getValidatePeriod()));
                searchValidator.setValidateTimeout(Beans.newDuration(l.getValidateTimeout()));
                pooledCf.setValidator(searchValidator);
                break;
        }

        pooledCf.setFailFastInitialize(l.isFailFast());

        if (StringUtils.isNotBlank(l.getPoolPassivator())) {
            val pass =
                AbstractLdapProperties.LdapConnectionPoolPassivator.valueOf(l.getPoolPassivator().toUpperCase());
            switch (pass) {
                case BIND:
                    if (StringUtils.isNotBlank(l.getBindDn()) && StringUtils.isNoneBlank(l.getBindCredential())) {
                        val bindRequest = new SimpleBindRequest(l.getBindDn(), l.getBindCredential());
                        pooledCf.setPassivator(new BindConnectionPassivator(bindRequest));
                        LOGGER.debug("Created [{}] passivator for [{}]", l.getPoolPassivator(), l.getLdapUrl());
                    } else {
                        val values = Arrays.stream(AbstractLdapProperties.LdapConnectionPoolPassivator.values())
                            .filter(v -> v != AbstractLdapProperties.LdapConnectionPoolPassivator.BIND)
                            .collect(Collectors.toList());
                        LOGGER.warn("[{}] pool passivator could not be created for [{}] given bind credentials are not specified. "
                                + "If you are dealing with LDAP in such a way that does not require bind credentials, you may need to "
                                + "set the pool passivator setting to one of [{}]",
                            l.getPoolPassivator(), l.getLdapUrl(), values);
                    }
                    break;
                default:
                    break;
            }
        }

        LOGGER.debug("Initializing ldap connection pool for [{}] and bindDn [{}]", l.getLdapUrl(), l.getBindDn());
        pooledCf.initialize();
        return pooledCf;
    }

    /**
     * New connection config connection config.
     *
     * @param properties the ldap properties
     * @return the connection config
     */
    public static ConnectionConfig newLdaptiveConnectionConfig(final AbstractLdapProperties properties) {
        if (StringUtils.isBlank(properties.getLdapUrl())) {
            throw new IllegalArgumentException("LDAP url cannot be empty/blank");
        }

        LOGGER.debug("Creating LDAP connection configuration for [{}]", properties.getLdapUrl());
        val cc = new ConnectionConfig();

        val urls = properties.getLdapUrl().contains(" ")
            ? properties.getLdapUrl()
            : String.join(" ", properties.getLdapUrl().split(","));
        LOGGER.debug("Transformed LDAP urls from [{}] to [{}]", properties.getLdapUrl(), urls);
        cc.setLdapUrl(urls);

        cc.setUseStartTLS(properties.isUseStartTls());
        cc.setConnectTimeout(Beans.newDuration(properties.getConnectTimeout()));
        cc.setResponseTimeout(Beans.newDuration(properties.getResponseTimeout()));

        if (StringUtils.isNotBlank(properties.getConnectionStrategy())) {
            val strategy = AbstractLdapProperties.LdapConnectionStrategy.valueOf(properties.getConnectionStrategy());
            switch (strategy) {
                case RANDOM:
                    cc.setConnectionStrategy(new RandomConnectionStrategy());
                    break;
                case DNS_SRV:
                    cc.setConnectionStrategy(new DnsSrvConnectionStrategy());
                    break;
                case ROUND_ROBIN:
                    cc.setConnectionStrategy(new RoundRobinConnectionStrategy());
                    break;
                case ACTIVE_PASSIVE:
                default:
                    cc.setConnectionStrategy(new ActivePassiveConnectionStrategy());
                    break;
            }
        }

        if (properties.getTrustCertificates() != null) {
            LOGGER.debug("Creating LDAP SSL configuration via trust certificates [{}]", properties.getTrustCertificates());
            val cfg = new X509CredentialConfig();
            cfg.setTrustCertificates(properties.getTrustCertificates());
            cc.setSslConfig(new SslConfig(cfg));
        } else if (properties.getTrustStore() != null || properties.getKeystore() != null) {
            val cfg = new KeyStoreCredentialConfig();
            if (properties.getTrustStore() != null) {
                LOGGER.trace("Creating LDAP SSL configuration with truststore [{}]", properties.getTrustStore());
                cfg.setTrustStore(properties.getTrustStore());
                cfg.setTrustStoreType(properties.getTrustStoreType());
                cfg.setTrustStorePassword(properties.getTrustStorePassword());
            }
            if (properties.getKeystore() != null) {
                LOGGER.trace("Creating LDAP SSL configuration via keystore [{}]", properties.getKeystore());
                cfg.setKeyStore(properties.getKeystore());
                cfg.setKeyStoreType(properties.getKeystoreType());
                cfg.setKeyStorePassword(properties.getKeystorePassword());
            }
            cc.setSslConfig(new SslConfig(cfg));
        } else {
            LOGGER.debug("Creating LDAP SSL configuration via the native JVM truststore");
            cc.setSslConfig(new SslConfig());
        }

        val sslConfig = cc.getSslConfig();
        if (sslConfig != null) {
            switch (properties.getHostnameVerifier()) {
                case ANY:
                    sslConfig.setHostnameVerifier(new AllowAnyHostnameVerifier());
                    break;
                case DEFAULT:
                default:
                    sslConfig.setHostnameVerifier(new DefaultHostnameVerifier());
            }

            if (StringUtils.isNotBlank(properties.getTrustManager())) {
                switch (AbstractLdapProperties.LdapTrustManagerOptions.valueOf(properties.getTrustManager().trim().toUpperCase())) {
                    case ANY:
                        sslConfig.setTrustManagers(new AllowAnyTrustManager());
                        break;
                    case DEFAULT:
                    default:
                        sslConfig.setTrustManagers(new DefaultTrustManager());
                        break;
                }
            }
        }

        if (StringUtils.isNotBlank(properties.getSaslMechanism())) {
            LOGGER.debug("Creating LDAP SASL mechanism via [{}]", properties.getSaslMechanism());

            val bc = new BindConnectionInitializer();
            val sc = getSaslConfigFrom(properties);

            if (StringUtils.isNotBlank(properties.getSaslAuthorizationId())) {
                sc.setAuthorizationId(properties.getSaslAuthorizationId());
            }
            sc.setMutualAuthentication(properties.getSaslMutualAuth());
            if (StringUtils.isNotBlank(properties.getSaslQualityOfProtection())) {
                sc.setQualityOfProtection(QualityOfProtection.valueOf(properties.getSaslQualityOfProtection()));
            }
            if (StringUtils.isNotBlank(properties.getSaslSecurityStrength())) {
                sc.setSecurityStrength(SecurityStrength.valueOf(properties.getSaslSecurityStrength()));
            }
            bc.setBindSaslConfig(sc);
            cc.setConnectionInitializers(bc);
        } else if (StringUtils.equals(properties.getBindCredential(), "*") && StringUtils.equals(properties.getBindDn(), "*")) {
            LOGGER.debug("Creating LDAP fast-bind connection initializer");
            cc.setConnectionInitializers(new FastBindConnectionInitializer());
        } else if (StringUtils.isNotBlank(properties.getBindDn()) && StringUtils.isNotBlank(properties.getBindCredential())) {
            LOGGER.debug("Creating LDAP bind connection initializer via [{}]", properties.getBindDn());
            cc.setConnectionInitializers(new BindConnectionInitializer(properties.getBindDn(), new Credential(properties.getBindCredential())));
        }
        return cc;
    }

    /**
     * Returns a pooled connection factory or default connection factory based on {@link AbstractLdapProperties#isDisablePooling()}.
     *
     * @param l ldap properties
     * @return the connection factory
     */
    public static ConnectionFactory newLdaptiveConnectionFactory(final AbstractLdapProperties l) {
        return l.isDisablePooling() ? newLdaptiveDefaultConnectionFactory(l) : newLdaptivePooledConnectionFactory(l);
    }

    /**
     * New dn resolver entry resolver.
     * Creates the necessary search entry resolver.
     *
     * @param l       the ldap settings
     * @param factory the factory
     * @return the entry resolver
     */
    public static EntryResolver newLdaptiveSearchEntryResolver(final AbstractLdapAuthenticationProperties l,
                                                               final ConnectionFactory factory) {

        var resolvers = Arrays.stream(StringUtils.split(l.getBaseDn(), BASE_DN_DELIMITER))
            .map(baseDn -> {
                val entryResolver = new SearchEntryResolver();
                entryResolver.setBaseDn(baseDn.trim());
                entryResolver.setUserFilter(l.getSearchFilter());
                entryResolver.setSubtreeSearch(l.isSubtreeSearch());
                entryResolver.setConnectionFactory(factory);
                entryResolver.setAllowMultipleEntries(l.isAllowMultipleEntries());
                entryResolver.setBinaryAttributes(l.getBinaryAttributes().toArray(new String[0]));
                
                if (StringUtils.isNotBlank(l.getDerefAliases())) {
                    entryResolver.setDerefAliases(DerefAliases.valueOf(l.getDerefAliases()));
                }

                val entryHandlers = newLdaptiveEntryHandlers(l.getSearchEntryHandlers());
                val searchResultHandlers = newLdaptiveSearchResultHandlers(l.getSearchEntryHandlers());
                if (!entryHandlers.isEmpty()) {
                    LOGGER.debug("Search entry handlers defined for the entry resolver of [{}] are [{}]", l.getLdapUrl(), entryHandlers);
                    entryResolver.setEntryHandlers(entryHandlers.toArray(LdapEntryHandler[]::new));
                }
                if (!searchResultHandlers.isEmpty()) {
                    LOGGER.debug("Search entry handlers defined for the entry resolver of [{}] are [{}]", l.getLdapUrl(), searchResultHandlers);
                    entryResolver.setSearchResultHandlers(searchResultHandlers.toArray(SearchResultHandler[]::new));
                }
                if (l.isFollowReferrals()) {
                    entryResolver.setSearchResultHandlers(new FollowSearchReferralHandler());
                }
                return entryResolver;
            })
            .collect(Collectors.toList());
        return new ChainingLdapEntryResolver(resolvers);
    }

    /**
     * New list of ldap entry handlers derived from the supplied properties.
     *
     * @param properties to inspect
     * @return the list of entry handlers
     */
    public static List<LdapEntryHandler> newLdaptiveEntryHandlers(final List<LdapSearchEntryHandlersProperties> properties) {
        val entryHandlers = new ArrayList<LdapEntryHandler>();
        properties.forEach(h -> {
            switch (h.getType()) {
                case ACTIVE_DIRECTORY:
                    entryHandlers.add(new ActiveDirectoryLdapEntryHandler());
                    break;
                case CASE_CHANGE:
                    val eh = new CaseChangeEntryHandler();
                    val caseChange = h.getCaseChange();
                    eh.setAttributeNameCaseChange(CaseChangeEntryHandler.CaseChange.valueOf(caseChange.getAttributeNameCaseChange()));
                    eh.setAttributeNames(caseChange.getAttributeNames().toArray(ArrayUtils.EMPTY_STRING_ARRAY));
                    eh.setAttributeValueCaseChange(CaseChangeEntryHandler.CaseChange.valueOf(caseChange.getAttributeValueCaseChange()));
                    eh.setDnCaseChange(CaseChangeEntryHandler.CaseChange.valueOf(caseChange.getDnCaseChange()));
                    entryHandlers.add(eh);
                    break;
                case DN_ATTRIBUTE_ENTRY:
                    val ehd = new DnAttributeEntryHandler();
                    val dnAttribute = h.getDnAttribute();
                    ehd.setAddIfExists(dnAttribute.isAddIfExists());
                    ehd.setDnAttributeName(dnAttribute.getDnAttributeName());
                    entryHandlers.add(ehd);
                    break;
                case MERGE:
                    val ehm = new MergeAttributeEntryHandler();
                    val mergeAttribute = h.getMergeAttribute();
                    ehm.setAttributeNames(mergeAttribute.getAttributeNames().toArray(ArrayUtils.EMPTY_STRING_ARRAY));
                    ehm.setMergeAttributeName(mergeAttribute.getMergeAttributeName());
                    entryHandlers.add(ehm);
                    break;
                case OBJECT_GUID:
                    entryHandlers.add(new ObjectGuidHandler());
                    break;
                case OBJECT_SID:
                    entryHandlers.add(new ObjectSidHandler());
                    break;
                default:
                    break;
            }
        });
        return entryHandlers;
    }

    /**
     * New list of ldap search result handlers derived from the supplied properties.
     *
     * @param properties to inspect
     * @return the list of search result handlers
     */
    public static List<SearchResultHandler> newLdaptiveSearchResultHandlers(final List<LdapSearchEntryHandlersProperties> properties) {
        val searchResultHandlers = new ArrayList<SearchResultHandler>();
        properties.forEach(h -> {
            switch (h.getType()) {
                case PRIMARY_GROUP:
                    val ehp = new PrimaryGroupIdHandler();
                    val primaryGroupId = h.getPrimaryGroupId();
                    ehp.setBaseDn(primaryGroupId.getBaseDn());
                    ehp.setGroupFilter(primaryGroupId.getGroupFilter());
                    searchResultHandlers.add(ehp);
                    break;
                case RANGE_ENTRY:
                    searchResultHandlers.add(new RangeEntryHandler());
                    break;
                case RECURSIVE_ENTRY:
                    val recursive = h.getRecursive();
                    searchResultHandlers.add(
                        new RecursiveResultHandler(recursive.getSearchAttribute(),
                            recursive.getMergeAttributes().toArray(ArrayUtils.EMPTY_STRING_ARRAY)));
                    break;
                case MERGE_ENTRIES:
                default:
                    searchResultHandlers.add(new MergeResultHandler());
                    break;
            }
        });
        return searchResultHandlers;
    }

    private static Authenticator getAuthenticatedOrAnonSearchAuthenticator(final AbstractLdapAuthenticationProperties l) {
        if (StringUtils.isBlank(l.getBaseDn())) {
            throw new IllegalArgumentException("Base dn cannot be empty/blank for authenticated/anonymous authentication");
        }
        if (StringUtils.isBlank(l.getSearchFilter())) {
            throw new IllegalArgumentException("User filter cannot be empty/blank for authenticated/anonymous authentication");
        }
        val connectionFactoryForSearch = newLdaptiveConnectionFactory(l);
        val resolver = buildAggregateDnResolver(l, connectionFactoryForSearch);

        val auth = StringUtils.isBlank(l.getPrincipalAttributePassword())
            ? new Authenticator(resolver, getBindAuthenticationHandler(newLdaptiveConnectionFactory(l)))
            : new Authenticator(resolver, getCompareAuthenticationHandler(l, newLdaptiveConnectionFactory(l)));

        if (l.isEnhanceWithEntryResolver()) {
            auth.setEntryResolver(newLdaptiveSearchEntryResolver(l, newLdaptiveConnectionFactory(l)));
        }
        return auth;
    }

    private static Authenticator getDirectBindAuthenticator(final AbstractLdapAuthenticationProperties l) {
        if (StringUtils.isBlank(l.getDnFormat())) {
            throw new IllegalArgumentException("Dn format cannot be empty/blank for direct bind authentication");
        }
        return getAuthenticatorViaDnFormat(l);
    }

    private static Authenticator getActiveDirectoryAuthenticator(final AbstractLdapAuthenticationProperties l) {
        if (StringUtils.isBlank(l.getDnFormat())) {
            throw new IllegalArgumentException("Dn format cannot be empty/blank for active directory authentication");
        }
        return getAuthenticatorViaDnFormat(l);
    }

    private static Authenticator getAuthenticatorViaDnFormat(final AbstractLdapAuthenticationProperties l) {
        val resolver = new FormatDnResolver(l.getDnFormat());
        val authenticator = new Authenticator(resolver, getBindAuthenticationHandler(newLdaptiveConnectionFactory(l)));

        if (l.isEnhanceWithEntryResolver()) {
            authenticator.setEntryResolver(newLdaptiveSearchEntryResolver(l, newLdaptiveConnectionFactory(l)));
        }
        return authenticator;
    }

    private static SimpleBindAuthenticationHandler getBindAuthenticationHandler(final ConnectionFactory factory) {
        val handler = new SimpleBindAuthenticationHandler(factory);
        return handler;
    }

    private static CompareAuthenticationHandler getCompareAuthenticationHandler(final AbstractLdapAuthenticationProperties l,
                                                                                final ConnectionFactory factory) {
        val handler = new CompareAuthenticationHandler(factory);
        handler.setPasswordAttribute(l.getPrincipalAttributePassword());
        return handler;
    }

    private static SaslConfig getSaslConfigFrom(final AbstractLdapProperties l) {

        if (Mechanism.valueOf(l.getSaslMechanism()) == Mechanism.DIGEST_MD5) {
            val sc = new SaslConfig();
            sc.setMechanism(Mechanism.DIGEST_MD5);
            sc.setRealm(l.getSaslRealm());
            return sc;
        }
        if (Mechanism.valueOf(l.getSaslMechanism()) == Mechanism.CRAM_MD5) {
            val sc = new SaslConfig();
            sc.setMechanism(Mechanism.CRAM_MD5);
            return sc;
        }
        if (Mechanism.valueOf(l.getSaslMechanism()) == Mechanism.EXTERNAL) {
            val sc = new SaslConfig();
            sc.setMechanism(Mechanism.EXTERNAL);
            return sc;
        }
        val sc = new SaslConfig();
        sc.setMechanism(Mechanism.GSSAPI);
        sc.setRealm(l.getSaslRealm());
        return sc;
    }

    /**
     * New default connection factory.
     *
     * @param l the l
     * @return the connection factory
     */
    private static DefaultConnectionFactory newLdaptiveDefaultConnectionFactory(final AbstractLdapProperties l) {
        LOGGER.debug("Creating LDAP connection factory for [{}]", l.getLdapUrl());
        val cc = newLdaptiveConnectionConfig(l);
        return new DefaultConnectionFactory(cc);
    }

    private static DnResolver buildAggregateDnResolver(final AbstractLdapAuthenticationProperties l,
                                                       final ConnectionFactory connectionFactory) {
        var resolvers = Arrays.stream(StringUtils.split(l.getBaseDn(), BASE_DN_DELIMITER))
            .map(baseDn -> {
                val resolver = new SearchDnResolver();
                resolver.setBaseDn(baseDn);
                resolver.setSubtreeSearch(l.isSubtreeSearch());
                resolver.setAllowMultipleDns(l.isAllowMultipleDns());
                resolver.setConnectionFactory(connectionFactory);
                resolver.setUserFilter(l.getSearchFilter());
                resolver.setResolveFromAttribute(l.getResolveFromAttribute());
                if (l.isFollowReferrals()) {
                    resolver.setSearchResultHandlers(new FollowSearchReferralHandler());
                }
                if (StringUtils.isNotBlank(l.getDerefAliases())) {
                    resolver.setDerefAliases(DerefAliases.valueOf(l.getDerefAliases()));
                }
                return resolver;
            })
            .collect(Collectors.toList());
        return new ChainingLdapDnResolver(resolvers);
    }

    /**
     * Create ldap authentication factory bean set factory bean.
     *
     * @return the set factory bean
     */
    public static SetFactoryBean createLdapAuthenticationFactoryBean() {
        val bean = new SetFactoryBean() {
            @Override
            protected void destroyInstance(final Set set) {
                set.forEach(Unchecked.consumer(handler ->
                    ((DisposableBean) handler).destroy()
                ));
            }
        };
        bean.setSourceSet(new HashSet<>());
        return bean;
    }

    private static AuthenticationPasswordPolicyHandlingStrategy<AuthenticationResponse, PasswordPolicyContext>
        createLdapPasswordPolicyHandlingStrategy(final LdapAuthenticationProperties l, final ApplicationContext applicationContext) {
        if (l.getPasswordPolicy().getStrategy() == LdapPasswordPolicyProperties.PasswordPolicyHandlingOptions.REJECT_RESULT_CODE) {
            LOGGER.debug("Created LDAP password policy handling strategy based on blocked authentication result codes");
            return new RejectResultCodeLdapPasswordPolicyHandlingStrategy();
        }

        val location = l.getPasswordPolicy().getGroovy().getLocation();
        if (l.getPasswordPolicy().getStrategy() == LdapPasswordPolicyProperties.PasswordPolicyHandlingOptions.GROOVY && location != null) {
            LOGGER.debug("Created LDAP password policy handling strategy based on Groovy script [{}]", location);
            return new GroovyPasswordPolicyHandlingStrategy(location, applicationContext);
        }

        LOGGER.debug("Created default LDAP password policy handling strategy");
        return new DefaultPasswordPolicyHandlingStrategy();
    }


    private static PasswordPolicyContext createLdapPasswordPolicyConfiguration(final LdapPasswordPolicyProperties passwordPolicy,
                                                                               final Authenticator authenticator,
                                                                               final Multimap<String, Object> attributes) {
        val cfg = new PasswordPolicyContext(passwordPolicy);
        val requestHandlers = new HashSet<>();
        val responseHandlers = new HashSet<>();

        val customPolicyClass = passwordPolicy.getCustomPolicyClass();
        if (StringUtils.isNotBlank(customPolicyClass)) {
            try {
                LOGGER.debug("Configuration indicates use of a custom password policy handler [{}]", customPolicyClass);
                val clazz = (Class<AuthenticationResponseHandler>) Class.forName(customPolicyClass);
                responseHandlers.add(clazz.getDeclaredConstructor().newInstance());
            } catch (final Exception e) {
                LoggingUtils.warn(LOGGER, "Unable to construct an instance of the password policy handler", e);
            }
        }
        LOGGER.debug("Password policy authentication response handler is set to accommodate directory type: [{}]", passwordPolicy.getType());
        switch (passwordPolicy.getType()) {
            case AD:
                responseHandlers.add(new ActiveDirectoryAuthenticationResponseHandler(Period.ofDays(cfg.getPasswordWarningNumberOfDays())));
                Arrays.stream(ActiveDirectoryAuthenticationResponseHandler.ATTRIBUTES).forEach(a -> {
                    LOGGER.debug("Configuring authentication to retrieve password policy attribute [{}]", a);
                    attributes.put(a, a);
                });
                break;
            case FreeIPA:
                Arrays.stream(FreeIPAAuthenticationResponseHandler.ATTRIBUTES).forEach(a -> {
                    LOGGER.debug("Configuring authentication to retrieve password policy attribute [{}]", a);
                    attributes.put(a, a);
                });
                responseHandlers.add(new FreeIPAAuthenticationResponseHandler(
                    Period.ofDays(cfg.getPasswordWarningNumberOfDays()), cfg.getLoginFailures()));
                break;
            case EDirectory:
                Arrays.stream(EDirectoryAuthenticationResponseHandler.ATTRIBUTES).forEach(a -> {
                    LOGGER.debug("Configuring authentication to retrieve password policy attribute [{}]", a);
                    attributes.put(a, a);
                });
                responseHandlers.add(new EDirectoryAuthenticationResponseHandler(Period.ofDays(cfg.getPasswordWarningNumberOfDays())));
                break;
            default:
                requestHandlers.add(new PasswordPolicyAuthenticationRequestHandler());
                responseHandlers.add(new PasswordPolicyAuthenticationResponseHandler());
                responseHandlers.add(new PasswordExpirationAuthenticationResponseHandler());
                break;
        }
        if (!requestHandlers.isEmpty()) {
            authenticator.setRequestHandlers(requestHandlers.toArray(AuthenticationRequestHandler[]::new));
        }
        authenticator.setResponseHandlers(responseHandlers.toArray(AuthenticationResponseHandler[]::new));

        LOGGER.debug("LDAP authentication response handlers configured are: [{}]", responseHandlers);

        if (!passwordPolicy.isAccountStateHandlingEnabled()) {
            cfg.setAccountStateHandler((response, configuration) -> new ArrayList<>(0));
            LOGGER.trace("Handling LDAP account states is disabled via CAS configuration");
        } else if (StringUtils.isNotBlank(passwordPolicy.getWarningAttributeName()) && StringUtils.isNotBlank(passwordPolicy.getWarningAttributeValue())) {
            val accountHandler = new OptionalWarningLdapAccountStateHandler();
            accountHandler.setDisplayWarningOnMatch(passwordPolicy.isDisplayWarningOnMatch());
            accountHandler.setWarnAttributeName(passwordPolicy.getWarningAttributeName());
            accountHandler.setWarningAttributeValue(passwordPolicy.getWarningAttributeValue());
            accountHandler.setAttributesToErrorMap(passwordPolicy.getPolicyAttributes());
            cfg.setAccountStateHandler(accountHandler);
            LOGGER.debug("Configuring an warning account state handler for LDAP authentication for warning attribute [{}] and value [{}]",
                passwordPolicy.getWarningAttributeName(), passwordPolicy.getWarningAttributeValue());
        } else {
            val accountHandler = new DefaultLdapAccountStateHandler();
            accountHandler.setAttributesToErrorMap(passwordPolicy.getPolicyAttributes());
            cfg.setAccountStateHandler(accountHandler);
            LOGGER.debug("Configuring the default account state handler for LDAP authentication");
        }
        return cfg;
    }

    /**
     * Create ldap authentication handler.
     *
     * @param props              the ldap authentication properties
     * @param applicationContext the application context
     * @param servicesManager    the services manager
     * @param principalFactory   the principal factory
     * @return the ldap authentication handler
     */
    public static LdapAuthenticationHandler createLdapAuthenticationHandler(final LdapAuthenticationProperties props,
                                                                            final ApplicationContext applicationContext,
                                                                            final ServicesManager servicesManager,
                                                                            final PrincipalFactory principalFactory) {
        val multiMapAttributes = CoreAuthenticationUtils.transformPrincipalAttributesListIntoMultiMap(props.getPrincipalAttributeList());
        LOGGER.debug("Created and mapped principal attributes [{}] for [{}]...", multiMapAttributes, props.getLdapUrl());

        LOGGER.debug("Creating LDAP authenticator for [{}] and baseDn [{}]", props.getLdapUrl(), props.getBaseDn());
        val authenticator = LdapUtils.newLdaptiveAuthenticator(props);
        LOGGER.debug("Ldap authenticator configured with return attributes [{}] for [{}] and baseDn [{}]",
            multiMapAttributes.keySet(), props.getLdapUrl(), props.getBaseDn());

        LOGGER.debug("Creating LDAP password policy handling strategy for [{}]", props.getLdapUrl());
        val strategy = createLdapPasswordPolicyHandlingStrategy(props, applicationContext);

        LOGGER.debug("Creating LDAP authentication handler for [{}]", props.getLdapUrl());
        val handler = new LdapAuthenticationHandler(props.getName(),
            servicesManager, principalFactory,
            props.getOrder(), authenticator, strategy);
        handler.setCollectDnAttribute(props.isCollectDnAttribute());

        if (!props.getAdditionalAttributes().isEmpty()) {
            val additional = CoreAuthenticationUtils.transformPrincipalAttributesListIntoMultiMap(props.getAdditionalAttributes());
            multiMapAttributes.putAll(additional);
        }
        if (StringUtils.isNotBlank(props.getPrincipalDnAttributeName())) {
            handler.setPrincipalDnAttributeName(props.getPrincipalDnAttributeName());
        }
        handler.setAllowMultiplePrincipalAttributeValues(props.isAllowMultiplePrincipalAttributeValues());
        handler.setAllowMissingPrincipalAttributeValue(props.isAllowMissingPrincipalAttributeValue());
        handler.setPasswordEncoder(PasswordEncoderUtils.newPasswordEncoder(props.getPasswordEncoder(), applicationContext));
        handler.setPrincipalNameTransformer(PrincipalNameTransformerUtils.newPrincipalNameTransformer(props.getPrincipalTransformation()));

        if (StringUtils.isNotBlank(props.getCredentialCriteria())) {
            LOGGER.trace("Ldap authentication for [{}] is filtering credentials by [{}]",
                props.getLdapUrl(), props.getCredentialCriteria());
            handler.setCredentialSelectionPredicate(CoreAuthenticationUtils.newCredentialSelectionPredicate(props.getCredentialCriteria()));
        }

        if (StringUtils.isBlank(props.getPrincipalAttributeId())) {
            LOGGER.trace("No principal id attribute is found for LDAP authentication via [{}]", props.getLdapUrl());
        } else {
            handler.setPrincipalIdAttribute(props.getPrincipalAttributeId());
            LOGGER.trace("Using principal id attribute [{}] for LDAP authentication via [{}]",
                props.getPrincipalAttributeId(), props.getLdapUrl());
        }

        val passwordPolicy = props.getPasswordPolicy();
        if (passwordPolicy.isEnabled()) {
            LOGGER.trace("Password policy is enabled for [{}]. Constructing password policy configuration", props.getLdapUrl());
            val cfg = createLdapPasswordPolicyConfiguration(passwordPolicy, authenticator, multiMapAttributes);
            handler.setPasswordPolicyConfiguration(cfg);
        }

        val attributes = CollectionUtils.wrap(multiMapAttributes);
        handler.setPrincipalAttributeMap(attributes);

        LOGGER.debug("Initializing LDAP authentication handler for [{}]", props.getLdapUrl());
        handler.initialize();
        return handler;
    }

    @RequiredArgsConstructor
    private static class ChainingLdapDnResolver implements DnResolver {
        private final List<? extends DnResolver> resolvers;

        @Override
        @SneakyThrows
        public String resolve(final User user) {
            return resolvers.stream()
                .map(resolver -> FunctionUtils.doAndHandle(
                    () -> resolver.resolve(user),
                    throwable -> {
                        LoggingUtils.warn(LOGGER, throwable);
                        return null;
                    })
                    .get())
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new AccountNotFoundException("Unable to resolve user dn for " + user.getIdentifier()));
        }
    }

    @RequiredArgsConstructor
    private static class ChainingLdapEntryResolver implements EntryResolver {
        private final List<? extends EntryResolver> resolvers;

        @Override
        public LdapEntry resolve(final AuthenticationCriteria criteria, final AuthenticationHandlerResponse response) {
            return resolvers.stream()
                .map(resolver -> FunctionUtils.doAndHandle(
                    () -> resolver.resolve(criteria, response),
                    throwable -> {
                        LoggingUtils.warn(LOGGER, throwable);
                        return null;
                    })
                    .get())
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        }
    }
}
