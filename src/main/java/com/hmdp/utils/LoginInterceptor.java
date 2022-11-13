package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LoginInterceptor implements HandlerInterceptor {


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
/*
//        //1 获取session
//        HttpSession session = request.getSession();

        //1 获取请求头中的token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            //不存在，拦截 返回401状态码
            response.setStatus(401);
            return false;
        }


//        //2 获取session中的用户
//        Object user = session.getAttribute("user");
//        //3 判断用户是否存在
//        if(user == null){
//            //4 不存在，拦截 返回401状态码
//            response.setStatus(401);
//            return false;
//        }
        //基于token获取redis中的用户
        Map<Object, Object> userMap = redisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);
        if(userMap.isEmpty()){
            //4 不存在，拦截 返回401状态码
            response.setStatus(401);
            return false;
        }
        //将查询到的Hash数据转为UserDTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);


        //5 存在，保存用户信息到ThreadLocal
//        UserHolder.saveUser((UserDTO)user);
        UserHolder.saveUser(userDTO);

        //刷新token有效期
        redisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token,RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);
        //6 放行*/

        //添加了前置拦截器之后，只需要去判断是否需要拦截
        if(UserHolder.getUser() == null){
            //没有，拦截，设置状态码
            response.setStatus(401);
            return false;
        }
        //有用户放行
        return true;
    }

}
