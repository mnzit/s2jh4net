/**
 * Copyright © 2015 - 2017 EntDIY JavaEE Development Framework
 *
 * Site: https://www.entdiy.com, E-Mail: xautlx@hotmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.entdiy.security;

import com.entdiy.auth.entity.Account;
import com.entdiy.auth.entity.Privilege;
import com.entdiy.auth.entity.Role;
import com.entdiy.auth.entity.User;
import com.entdiy.auth.service.AccountService;
import com.entdiy.auth.service.UserService;
import lombok.Setter;
import org.apache.shiro.authc.*;
import org.apache.shiro.authc.credential.HashedCredentialsMatcher;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Optional;

public class AuthTypeShiroJdbcRealm extends AuthorizingRealm {

    @Setter
    private PasswordService passwordService;

    @Setter
    private AccountService accountService;

    @Setter
    private UserService userService;

    @Override
    public boolean supports(AuthenticationToken token) {
        return token instanceof AuthTypeUsernamePasswordToken;
    }

    /**
     * 认证回调函数,登录时调用.
     */
    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken authcToken) throws AuthenticationException {
        AuthTypeUsernamePasswordToken token = (AuthTypeUsernamePasswordToken) authcToken;
        String username = token.getUsername();
        Account account = accountService.findByUsername(token.getAuthType(), username);
        if (account == null) {
            throw new UnknownAccountException("登录账号或密码不正确");
        }

        //把盐值注入到用户输入密码，用于后续加密算法使用
        token.setPassword(passwordService.injectPasswordSalt(String.valueOf(token.getPassword()), account.getSalt()).toCharArray());

        //构造权限框架认证用户信息对象
        DefaultAuthUserDetails authUserDetails = new DefaultAuthUserDetails();
        authUserDetails.setDataDomain(account.getDataDomain());
        authUserDetails.setAccountId(account.getId());
        authUserDetails.setUsername(username);

        return new SimpleAuthenticationInfo(authUserDetails, account.getPassword(), "Admin Shiro JDBC Realm");
    }

    /**
     * 授权查询回调函数, 进行鉴权但缓存中无用户的授权信息时调用.
     */
    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
        DefaultAuthUserDetails authUserDetails = (DefaultAuthUserDetails) principals.getPrimaryPrincipal();
        SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();

        Optional<Account> account = accountService.findOne(authUserDetails.getAccountId());
        account.ifPresent(one -> {
            //管理端类型用户添加角色权限处理
            if (Account.AuthTypeEnum.admin.equals(one.getAuthType())) {
                User user = userService.findByAccount(one);

                //管理端登录来源
                info.addRole(DefaultAuthUserDetails.ROLE_MGMT_USER);

                //查询用户角色列表
                List<Role> userRoles = userService.findRoles(user);
                for (Role role : userRoles) {
                    info.addRole(role.getCode());
                }

                //超级管理员特殊处理
                for (String role : info.getRoles()) {
                    if (DefaultAuthUserDetails.ROLE_SUPER_USER.equals(role)) {
                        //追加超级权限配置
                        info.addStringPermission("*");
                        break;
                    }
                }

                //基于当前用户所有角色集合获取有效的权限集合
                List<Privilege> privileges = userService.findPrivileges(info.getRoles());
                for (Privilege privilege : privileges) {
                    info.addStringPermission(privilege.getCode());
                }
            }
        });
        return info;
    }

    @Override
    public AuthorizationInfo getAuthorizationInfo(PrincipalCollection principals) {
        return super.getAuthorizationInfo(principals);
    }

    /**
     * 设定Password校验的Hash算法与迭代次数.
     */
    @PostConstruct
    public void initCredentialsMatcher() {
        HashedCredentialsMatcher matcher = new HashedCredentialsMatcher(PasswordService.HASH_ALGORITHM);
        setCredentialsMatcher(matcher);
    }
}
