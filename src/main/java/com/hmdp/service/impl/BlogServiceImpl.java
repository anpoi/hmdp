package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
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
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jodd.util.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;
import static net.sf.jsqlparser.util.validation.metadata.NamedObject.user;

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

    @Autowired
    private IUserService userService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private IFollowService followService;

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
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        //1 查询blog
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("笔记不存在！");
        }

        //2 查询blog有关的用户
        queryBlogUser(blog);
        //3 查询blog是否别点赞了
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        //1 获取用户数据
        UserDTO userDTO = UserHolder.getUser();
        if(userDTO == null){
            //用户未登录，无需查询点赞列表
            return;
        }
        Long userId = userDTO.getId();

        //2 判断当前用户是否已经点赞
        String key = BLOG_LIKED_KEY+blog.getId();
        Double score = redisTemplate.opsForZSet().score(key, userId.toString());

        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        //1 获取用户数据
        Long userId = UserHolder.getUser().getId();

        //2 判断当前用户是否已经点赞
        String key = BLOG_LIKED_KEY+id;
        Double score = redisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null){
            //3 如果未点赞，可以点赞
            //3.1 数据库点赞数+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //3.2 保存用户到redis到zset集合 zadd key value score
            if(isSuccess){
                redisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else {
            //4 如果已点赞，取消点赞
            //4.1 数据库点赞数-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            //4.2 把用户从redis的set集合中溢出
            if(isSuccess){
                redisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //1 查询top5的点赞用户 zrange key 0 4
        String key = BLOG_LIKED_KEY+id;
        Set<String> top5 = redisTemplate.opsForZSet().range(key, 0, 4);
        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptySet());
        }
        //2 解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        //3 根据用户id查询用户12
        List<UserDTO> userDTOS = userService.query().in("id",ids)
                .last("order by field(id,"+idStr+")").list()
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());


        //4 返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        //1 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        //2 保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增补笔记失败！");
        }

        //3 查询笔记作者的所有粉丝 select * from tb_follow
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();

        //4 推送笔记id给所有粉丝
         for (Follow follow : follows){
             //4.1 获取粉丝id
             Long userId = follow.getUserId();
             //4.2 推送到收件箱
             String key = FEED_KEY + userId;
             redisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
         }
        //

        //3 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1 获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = FEED_KEY + userId;

        //2 查询收件箱 ZREVRANGBYSCORE key max min WITHSCORES LIMIT offset count
        Set<ZSetOperations.TypedTuple<String>> typedTuples = redisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 3);

        //非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }

        //3 解析收件箱：blogId、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int  os = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            //3.1 获取id
            ids.add(Long.valueOf(typedTuple.getValue()));

            //3.2 获取时间戳
            long time = typedTuple.getScore().longValue();
            if(time == minTime){
                os++;
            }else{
                minTime = time;
                os = 1;
            }
        }

        //4 根据id查询blog
        String idStr = StrUtil.join(",",ids);
        List<Blog> blogs = query().in("id",ids)
                .last("order by field(id,"+idStr+")").list();

        for (Blog blog : blogs) {
            //4.1 查询blog有关的用户
            queryBlogUser(blog);
            //4.2 查询blog是否别点赞了
            isBlogLiked(blog);
        }

        //5 封装并返回
        ScrollResult sr = new ScrollResult();
        sr.setList(blogs);
        sr.setOffset(os);
        sr.setMinTime(minTime);

        return Result.ok(sr);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
