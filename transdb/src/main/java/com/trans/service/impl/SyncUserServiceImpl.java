package com.trans.service.impl;

import cn.dev33.satoken.secure.BCrypt;
import cn.dev33.satoken.stp.StpUtil;
import com.trans.config.BusinessException;
import com.trans.config.Response;
import com.trans.config.ResultCodeEnum;
import com.trans.mapper.SyncUserMapper;
import com.trans.model.entity.SyncUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
public class SyncUserServiceImpl {

    @Autowired
    private SyncUserMapper userMapper;

    public Response login(String username, String password) {
        SyncUser user = userMapper.findByUsername(username);
        if (user == null) {
            throw new BusinessException(ResultCodeEnum.AUTH_ERROR, "用户名或密码错误");
        }
        if (!BCrypt.checkpw(password, user.getPassword())) {
            throw new BusinessException(ResultCodeEnum.AUTH_ERROR, "用户名或密码错误");
        }
        StpUtil.login(user.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", StpUtil.getTokenValue());
        result.put("username", user.getUsername());
        result.put("realName", user.getRealName());
        result.put("role", user.getRole());
        return Response.success(result);
    }

    public Response logout() {
        StpUtil.logout();
        return Response.success("已退出");
    }

    public Response userInfo() {
        String userId = StpUtil.getLoginIdAsString();
        SyncUser user = userMapper.getById(userId);
        if (user == null) {
            return Response.error(ResultCodeEnum.AUTH_ERROR, "用户不存在");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", user.getUsername());
        m.put("realName", user.getRealName());
        m.put("role", user.getRole());
        return Response.success(m);
    }
}
