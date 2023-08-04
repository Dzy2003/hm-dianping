package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.lang.func.Func1;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.判断用户传入的手机号是否合法
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号输入不合法");
        }
        //2.生成验证码
        String code = RandomUtil.randomNumbers(6);
        //3.保存到redis中
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code,
                RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        log.debug("发送的验证码为：" + code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        String password = loginForm.getPassword();
        String code = loginForm.getCode();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号输入不合法");
        }
        if(password == null && code == null) return Result.fail("密码和验证码不能均为空");
        //从redis中获取验证码
        String CacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        if(CacheCode == null || !code.equals(CacheCode)){
            return Result.fail("验证码输入错误");
        }
        User user = lambdaQuery().eq(User::getPhone, phone).one();
        if(user == null){
            User newUser = new User();
            newUser.setPhone(phone);
            newUser.setNickName(SystemConstants.USER_NICK_NAME_PREFIX +RandomUtil.randomString(10));
            save(newUser);
        }
        //将用户信息保存到redis中，并且生成一个token返回给前端
        UserDTO userDTO = BeanUtil.copyProperties(user,UserDTO.class);
        String token = UUID.randomUUID().toString(true);
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<String, Object> map = BeanUtil.beanToMap(userDTO);
        map.put("id",map.get("id").toString());
        stringRedisTemplate.opsForHash().putAll(key,map);
        //设置有效期
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL,TimeUnit.MINUTES);
        return Result.ok(token);
    }
}
