package com.scxd.controller;

import com.scxd.config.Response;
import com.scxd.service.impl.SyncUserServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @Autowired
    private SyncUserServiceImpl userService;

    @PostMapping("/login")
    public Response login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || password == null) {
            return Response.paramError("用户名和密码不能为空");
        }
        return userService.login(username, password);
    }

    @PostMapping("/logout")
    public Response logout() {
        return userService.logout();
    }

    @GetMapping("/user-info")
    public Response userInfo() {
        return userService.userInfo();
    }
}
