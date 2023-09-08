package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.api.R;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    UserServiceImpl userService;
    @Resource
    StringRedisTemplate stringRedisTemplate;

    /**
     * 分页查询笔记，按照点赞数排序
     * @param current 当前页码
     * @return 当页博客
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 查询博客详情
     * @param id 博客id
     * @return 博客详细信息
     */
    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }


    /**
     * 点赞博客
     * @param id 博客id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        //查找redis是否用户已经点赞
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //未点赞
        if(score == null){
            boolean success = lambdaUpdate().setSql("liked = liked + 1").eq(Blog::getId, id).update();
            if(success){
                stringRedisTemplate.opsForZSet().add(key, userId.toString(),System.currentTimeMillis());
            }
        }else{
            boolean success = lambdaUpdate().setSql("liked = liked - 1").eq(Blog::getId, id).update();
            if(success){
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查询博客的点赞前五个用户(按照点赞时间)
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(BLOG_LIKED_KEY + id, 0, 4);
        if(top5 == null || top5.isEmpty()) return  Result.ok(Collections.emptyList());
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> list = userService.lambdaQuery()
                .in(User::getId,ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(list);

    }

    /**
     * 封装blog与用户相关的信息
     * @param blog
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * 判断该用户是否已经给blog点赞
     * @param blog
     */
    private void isBlogLiked(Blog blog) {
        //获取登录用户
        UserDTO userDTO = UserHolder.getUser();
        //若用户未登录，则跳过该环节
        if(userDTO == null) return;
        Long userId = userDTO.getId();
        //查找redis是否用户已经点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }
}
