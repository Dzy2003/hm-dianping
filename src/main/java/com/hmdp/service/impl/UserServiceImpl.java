package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.func.Func1;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

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

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.判断用户传入的手机号是否合法
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号输入不合法");
        }
        //2.生成验证码
        String code = RandomUtil.randomNumbers(6);
        //3.保存到session
        session.setAttribute("code",code);
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
        Object CacheCode = session.getAttribute("code");
        if(CacheCode == null || !code.equals(CacheCode.toString())){
            return Result.fail("验证码输入错误");
        }
        User user = lambdaQuery().eq(User::getPhone, phone).one();
        if(user == null){
            User newUser = new User();
            newUser.setPhone(phone);
            newUser.setNickName(SystemConstants.USER_NICK_NAME_PREFIX +RandomUtil.randomString(10));
            save(newUser);
        }
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        return Result.ok();
    }
}
