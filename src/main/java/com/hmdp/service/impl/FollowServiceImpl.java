package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.api.R;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
@Slf4j
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    IUserService userService;
    /**
     * 关注功能
     * @param followUserId 被关注的用户id
     * @param isFollow 是否已经关注
     * @return 是否关注（取关）成功
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long id = UserHolder.getUser().getId();//获取当前登录用户
        String followKey = "follows:" + id;
        if(!isFollow){
            //已经关注，点击取关
            boolean success =
                    remove(lambdaQuery()
                    .getWrapper().
                    eq(Follow::getUserId, id).
                    eq(Follow::getFollowUserId, followUserId));
            if(success){
                stringRedisTemplate.opsForSet().remove(followKey,followUserId.toString());
            }
        }else{
            //未关注 ，点击关注
            Follow follow = new Follow();
            follow.setUserId(id);
            follow.setFollowUserId(followUserId);
            boolean success = save(follow);
            if(success){
                stringRedisTemplate.opsForSet().add(followKey,followUserId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查询用户是否关注该用户
     * @param followUserId 该用户id
     * @return Boolean 是否关注
     */
    @Override
    public Result isFollow(Long followUserId) {
        Long id = UserHolder.getUser().getId();//获取当前登录用户
        //根据userId和followUserId查询记录，若记录不为0则关注
        Integer count = lambdaQuery().eq(Follow::getUserId, id).eq(Follow::getFollowUserId, followUserId).count();
        return Result.ok(count > 0);
    }

    /**
     * 求两个用户共同关注
     * @param id 另一个用户id
     * @return 两个用户的共同关注
     */
    @Override
    public Result followCommons(Long id) {
        Long userId = UserHolder.getUser().getId();
        //当前用户id和另一个用户id关注列表的键
        String Key1 = "follows:" + id;
        String Key2 = "follows:" + userId;
        ////对两个用户的关注集合求交集
        Set<String> Commons = stringRedisTemplate.opsForSet().intersect(Key1, Key2);
        log.info("Following:"+Commons.toString());
        //通过交集查询的用户id获取用户信息
        List<UserDTO> userDTOS = Commons.stream().map(uidStr -> {
            Long uid = Long.valueOf(uidStr);
            User user = userService.getById(uid);
            return BeanUtil.copyProperties(user, UserDTO.class);
        }).collect(Collectors.toList());
        log.info("users:"+userDTOS);
        return Result.ok(userDTOS);
    }
}
