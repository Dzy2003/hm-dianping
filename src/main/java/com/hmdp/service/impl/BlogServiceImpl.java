package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.api.R;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
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
    FollowServiceImpl followService;
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
        //从redis中读取前五的id
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(BLOG_LIKED_KEY + id, 0, 4);
        //为空返回空集合
        if(top5 == null || top5.isEmpty()) return  Result.ok(Collections.emptyList());
        //将id字符串解析为Long
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        //将id作为字符串并且以','分割
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> list = userService.lambdaQuery()
                .in(User::getId,ids)//根据ids查询
                .last("ORDER BY FIELD(id," + idStr + ")")//保证查询的id的顺序与我们传入的顺序相同
                .list()
                //使用stream流将user对象转为userDTO对象
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(list);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean success = save(blog);
        if(!success){
            return Result.fail("新增笔记失败");
        }
        followService.lambdaQuery()
                .eq(Follow::getFollowUserId, user.getId())
                .list()
                .forEach(follow -> {
                    Long id = follow.getUserId();
                    String key = RedisConstants.FEED_KEY + id;
                    stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
                });
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.找到当前用户
        Long id = UserHolder.getUser().getId();
        //获取当前用户收件箱
        String key = RedisConstants.FEED_KEY + id;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //收件箱是否为空
        if(typedTuples == null || typedTuples.isEmpty()) return Result.ok();
        // 4.解析数据：blogId、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            long time = typedTuple.getScore().longValue();
            ids.add(Long.valueOf(typedTuple.getValue()));
            if(time == minTime){
                os++;
            }else{
                minTime = time;
                os = 1;
            }
        }
        // 5.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = lambdaQuery().in(Blog::getId, ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for (Blog blog : blogs) {
            // 5.1.查询blog有关的用户
            queryBlogUser(blog);
            // 5.2.查询blog是否被点赞
            isBlogLiked(blog);
        }

        // 6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);

        return Result.ok(r);

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
