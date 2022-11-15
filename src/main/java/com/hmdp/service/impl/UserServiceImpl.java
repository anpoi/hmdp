package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static String tokenKey = "";

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)){
            //2 如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }

        //3 符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

//        //4 保存验证码到session
//        session.setAttribute("code",code);

        //4 保存验证码到redis
        redisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //5 发送验证码
        log.debug("短信验证码发送成功，验证码："+code);

        //返回结果
        return Result.ok(code);
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1 检验手机号
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())){
            //2 如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //2 校验验证码
      /*  Object cacheCode = session.getAttribute("code");
        String code = loginForm.getCode();
        if(cacheCode==null || !cacheCode.toString().equals(code)){
            //3 不一致，报错
            return Result.fail("验证码错误！");
        }
        */
        //从redis中获取验证码校验
        String cacheCode = redisTemplate.opsForValue().get(LOGIN_CODE_KEY+loginForm.getPhone());
        String code = loginForm.getCode();
        if(cacheCode==null || !cacheCode.equals(code)){
            //3 不一致，报错
            return Result.fail("验证码错误！");
        }

        //4 根据手机号查询用户
        User user = query().eq("phone", loginForm.getPhone()).one();

        //5 判断用户是否存在
        if(user==null){
            //6 不存在,创建新用户并保存
            user = createUserWithPhone(loginForm.getPhone());
        }


//        //7 保存用户信息到session中
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

        //7 保存用户信息到redis中
        //7.1 随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //7.2 将User对象转为Hash存储

        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String,Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor(
                        (fileName,finalValue)->finalValue.toString()));
        //7.3 存储
        tokenKey = LOGIN_USER_KEY+token;
        redisTemplate.opsForHash().putAll(tokenKey,userMap);
        //7.4 设置token有效期
        redisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.SECONDS);
        //8 返回token
        return Result.ok(token);
    }

    @Override
    public Result logout() {
        //删除redis的缓存，将token有效期设置为0
        redisTemplate.expire(tokenKey,0,TimeUnit.SECONDS);
        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
        //创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        //保存用户
        save(user);
        return user;
    }
}
