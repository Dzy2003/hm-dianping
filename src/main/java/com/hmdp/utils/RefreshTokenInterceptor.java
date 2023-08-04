package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 刷新token
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {

        StringRedisTemplate stringRedisTemplate;
        public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
            this.stringRedisTemplate = stringRedisTemplate;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
            //从前请求头获取到token
            String token = request.getHeader("Authorization");
            if (StrUtil.isBlank(token)) {
                return true;
            }
            //从redis中获取用户信息
            Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);
            //放行
            if (userMap.isEmpty()) {
                return true;
            }
            //存在，保存用户信息到Threadlocal
            UserHolder.saveUser(BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false));
            //每次拦截请求都将token的有效期刷新
            stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token,
                    RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
            //放行
            return true;
        }

        @Override
        public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
            UserHolder.removeUser();
        }
}
