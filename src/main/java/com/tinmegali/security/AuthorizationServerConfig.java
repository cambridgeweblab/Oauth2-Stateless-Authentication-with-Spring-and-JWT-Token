package com.tinmegali.security;

import com.tinmegali.models.Account;
import com.tinmegali.services.AccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.common.DefaultOAuth2AccessToken;
import org.springframework.security.oauth2.config.annotation.configurers.ClientDetailsServiceConfigurer;
import org.springframework.security.oauth2.config.annotation.web.configuration.AuthorizationServerConfigurerAdapter;
import org.springframework.security.oauth2.config.annotation.web.configuration.EnableAuthorizationServer;
import org.springframework.security.oauth2.config.annotation.web.configurers.AuthorizationServerEndpointsConfigurer;
import org.springframework.security.oauth2.config.annotation.web.configurers.AuthorizationServerSecurityConfigurer;
import org.springframework.security.oauth2.provider.token.DefaultTokenServices;
import org.springframework.security.oauth2.provider.token.TokenEnhancer;
import org.springframework.security.oauth2.provider.token.TokenEnhancerChain;
import org.springframework.security.oauth2.provider.token.TokenStore;
import org.springframework.security.oauth2.provider.token.store.JwtAccessTokenConverter;
import org.springframework.security.oauth2.provider.token.store.JwtTokenStore;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.*;

import static java.util.stream.Collectors.toList;

@Configuration
@EnableAuthorizationServer
public class AuthorizationServerConfig extends AuthorizationServerConfigurerAdapter {

    @Value("${security.oauth2.resource.id}")
    private String resourceId;

    @Value("${access_token.validity_period}")
    private int accessTokenValiditySeconds;

    @Value("${refresh_token.validity_period}")
    private int refreshTokenValiditySeconds;

    /** Namespace to use for user_name and authorities so that we're OIDC conformant */
    @Value("${oidc_namespace:http://name.space/}")
    private String namespace;

    @Autowired
    private AuthenticationManager authenticationManager;

    @Bean
    public UserDetailsService userDetailsService(){
        return new AccountService();
    }

    @Override
    public void configure(AuthorizationServerEndpointsConfigurer endpoints) throws Exception {
        endpoints
                .authenticationManager(this.authenticationManager)
                .tokenServices(tokenServices())
                .tokenStore(tokenStore())
                .accessTokenConverter(accessTokenConverter());
    }

    @Override
    public void configure(AuthorizationServerSecurityConfigurer oauthServer) throws Exception {
        oauthServer
                .tokenKeyAccess("isAnonymous() || hasAuthority('ROLE_TRUSTED_CLIENT')")
                .checkTokenAccess("hasAuthority('ROLE_TRUSTED_CLIENT')");
    }

    @Override
    public void configure(ClientDetailsServiceConfigurer clients) throws Exception {
        clients.inMemory()
                .withClient("normal-app")
                    .authorizedGrantTypes("authorization_code", "implicit")
                    .authorities("ROLE_CLIENT")
                    .scopes("read", "write")
                    .resourceIds(resourceId)
                    .accessTokenValiditySeconds(accessTokenValiditySeconds)
                    .refreshTokenValiditySeconds(refreshTokenValiditySeconds)
                    .and()
                .withClient("trusted-app")
                    .authorizedGrantTypes("client_credentials", "password", "refresh_token")
                    .authorities("ROLE_TRUSTED_CLIENT")
                    .scopes("read", "write")
                    .resourceIds(resourceId)
                    .accessTokenValiditySeconds(accessTokenValiditySeconds)
                    .refreshTokenValiditySeconds(refreshTokenValiditySeconds)
                    .secret("secret")
                    .and()
                .withClient("register-app")
                    .authorizedGrantTypes("client_credentials")
                    .authorities("ROLE_REGISTER")
                    .scopes("read")
                    .resourceIds(resourceId)
                    .secret("secret")
                .and()
                    .withClient("my-client-with-registered-redirect")
                    .authorizedGrantTypes("authorization_code")
                    .authorities("ROLE_CLIENT")
                    .scopes("read", "trust")
                    .resourceIds("oauth2-resource")
                    .redirectUris("http://anywhere?key=value");
    }

    @Bean
    public TokenStore tokenStore() {
        return new JwtTokenStore(accessTokenConverter());
    }

    @Autowired
    private SecretKeyProvider keyProvider;

    @Bean
    public JwtAccessTokenConverter accessTokenConverter() {
        JwtAccessTokenConverter converter = new JwtAccessTokenConverter();
        try {
            converter.setSigningKey(keyProvider.getKey());
        } catch (URISyntaxException | KeyStoreException | NoSuchAlgorithmException | IOException | UnrecoverableKeyException | CertificateException e) {
            e.printStackTrace();
        }

        return converter;
    }

    @Bean
    @Primary
    public DefaultTokenServices tokenServices() {
        TokenEnhancerChain enhancersChain = new TokenEnhancerChain();

        List<TokenEnhancer> enhancersList = new ArrayList<>(2);
        enhancersList.add(applyNamespaceTokenEnhancer());
        enhancersList.add(accessTokenConverter());
        enhancersChain.setTokenEnhancers(enhancersList);

        DefaultTokenServices defaultTokenServices = new DefaultTokenServices();
        defaultTokenServices.setTokenStore(tokenStore());
        defaultTokenServices.setSupportRefreshToken(true);
        defaultTokenServices.setTokenEnhancer(enhancersChain);
        return defaultTokenServices;
    }

    private TokenEnhancer applyNamespaceTokenEnhancer() {
        return (accessToken, authentication) -> {
                List<String> authorities = authentication.getAuthorities()
                        .stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(toList());

                Map<String, Object> newTokenInfo = new LinkedHashMap<>(accessToken.getAdditionalInformation());
                newTokenInfo.put(namespace + "user_name", authentication.getName());
                newTokenInfo.put(namespace + "authorities", authorities);
                Account account = (Account) authentication.getUserAuthentication().getPrincipal();
                newTokenInfo.put(namespace + "givenName", account.getFirstName());
                DefaultOAuth2AccessToken result = new DefaultOAuth2AccessToken(accessToken);
                result.setAdditionalInformation(newTokenInfo);
                return result;
            };
    }
}
