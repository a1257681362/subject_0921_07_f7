package com.evops.config;

import com.evops.auth.shiro.JwtFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shiro.mgt.DefaultSessionStorageEvaluator;
import org.apache.shiro.mgt.DefaultSubjectDAO;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.realm.SimpleAccountRealm;
import org.apache.shiro.spring.web.config.DefaultShiroFilterChainDefinition;
import org.apache.shiro.spring.web.config.ShiroFilterChainDefinition;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.subject.SubjectContext;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.apache.shiro.web.mgt.DefaultWebSubjectFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shiro 装配：JWT 无状态会话。
 * - 双 Realm：SimpleAccountRealm 负责登录时的账号密码校验，JwtRealm（@Component，自动收集）
 *   负责请求中 JWT 的登录态建立，按各自 supports(token) 路由；
 * - 不替换 starter 的 securityManager（避免同名 Bean 冲突），改用 BeanPostProcessor 关闭
 *   会话创建/存储，每次请求只凭令牌现验，服务端不落 HttpSession；
 * - 业务接口走 jwt 过滤器，登录/刷新/健康检查放行；
 * - auth.enabled=false（测试）时全部放行，过滤链中不出现 jwt。
 */
@Configuration
public class ShiroConfig {

    /** 登录内置账号（起步阶段），账号来源 auth.accounts，密码不写进任何令牌。 */
    @Bean
    public Realm localRealm(AuthProperties properties) {
        SimpleAccountRealm realm = new SimpleAccountRealm("localAccountRealm");
        if (properties.isEnabled()) {
            for (AuthProperties.Account account : properties.getAccounts()) {
                realm.addAccount(account.getUsername(), account.getPassword(),
                        account.getRoles().toArray(new String[0]));
            }
        }
        return realm;
    }

    /**
     * 无状态定制 starter 装配的 DefaultWebSecurityManager：
     * 禁止创建会话、关闭会话存储。不新建 SecurityManager，避免与自动配置同名冲突。
     */
    @Bean
    public BeanPostProcessor statelessSecurityManagerCustomizer() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DefaultWebSecurityManager) {
                    DefaultWebSecurityManager securityManager = (DefaultWebSecurityManager) bean;
                    securityManager.setSubjectFactory(new DefaultWebSubjectFactory() {
                        @Override
                        public Subject createSubject(SubjectContext context) {
                            context.setSessionCreationEnabled(false);
                            return super.createSubject(context);
                        }
                    });
                    DefaultSubjectDAO subjectDAO = new DefaultSubjectDAO();
                    DefaultSessionStorageEvaluator storageEvaluator = new DefaultSessionStorageEvaluator();
                    storageEvaluator.setSessionStorageEnabled(false);
                    subjectDAO.setSessionStorageEvaluator(storageEvaluator);
                    securityManager.setSubjectDAO(subjectDAO);
                }
                return bean;
            }
        };
    }

    /** 过滤链里的 jwt 过滤器（bean 名即过滤器名，starter 自动收进 filterMap）。 */
    @Bean("jwt")
    public JwtFilter jwtFilter(com.evops.auth.jwt.JwtService jwtService,
                               com.evops.auth.service.BlacklistService blacklistService,
                               ObjectMapper objectMapper) {
        return new JwtFilter(jwtService, blacklistService, objectMapper);
    }

    /**
     * 防止 JwtFilter 被 Spring Boot 再注册到 Servlet 容器（它只属于 Shiro 过滤链，
     * 由 ShiroFilterFactoryBean 管理，重复注册会造成双份校验）。
     */
    @Bean
    public FilterRegistrationBean<JwtFilter> jwtFilterRegistration(JwtFilter jwtFilter) {
        FilterRegistrationBean<JwtFilter> registration = new FilterRegistrationBean<>(jwtFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public ShiroFilterChainDefinition shiroFilterChainDefinition(AuthProperties properties) {
        DefaultShiroFilterChainDefinition chain = new DefaultShiroFilterChainDefinition();
        chain.addPathDefinition("/api/health", "anon");
        chain.addPathDefinition("/api/auth/login", "anon");
        chain.addPathDefinition("/api/auth/refresh", "anon");
        chain.addPathDefinition("/error", "anon");

        if (properties.isEnabled()) {
            // 其余所有业务接口一律凭有效访问令牌
            chain.addPathDefinition("/**", "jwt");
        } else {
            // 测试环境关闭鉴权（application-test.yml auth.enabled=false）
            chain.addPathDefinition("/**", "anon");
        }
        return chain;
    }
}
