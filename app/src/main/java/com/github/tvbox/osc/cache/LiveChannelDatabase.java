package com.github.tvbox.osc.cache;

import androidx.room.Dao;
import androidx.room.Database;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.Update;
import android.content.Context;

import com.github.tvbox.osc.bean.LiveChannel;

import java.util.List;

/**
 * 直播频道本地数据库
 * 使用 Room 持久化频道数据、测速结果和缓存信息
 */
@Database(entities = {LiveChannel.class}, version = 2, exportSchema = false)
public abstract class LiveChannelDatabase extends RoomDatabase {

    private static volatile LiveChannelDatabase instance;

    public abstract LiveChannelDao channelDao();

    public static LiveChannelDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (LiveChannelDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                            context.getApplicationContext(),
                            LiveChannelDatabase.class,
                            "live_channels.db"
                    )
                    .fallbackToDestructiveMigration()
                    .build();
                }
            }
        }
        return instance;
    }

    /**
     * 频道数据访问对象
     */
    @Dao
    public interface LiveChannelDao {

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        void insertChannel(LiveChannel channel);

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        void insertChannels(List<LiveChannel> channels);

        @Update
        void updateChannel(LiveChannel channel);

        @Delete
        void deleteChannel(LiveChannel channel);

        @Query("DELETE FROM live_channels")
        void deleteAll();

        @Query("SELECT * FROM live_channels ORDER BY sortWeight, id")
        List<LiveChannel> getAllChannels();

        @Query("SELECT * FROM live_channels WHERE groupName = :groupName ORDER BY sortWeight")
        List<LiveChannel> getChannelsByGroup(String groupName);

        @Query("SELECT * FROM live_channels WHERE channelName LIKE '%' || :keyword || '%'")
        List<LiveChannel> searchChannels(String keyword);

        @Query("SELECT * FROM live_channels WHERE favorite = 1 ORDER BY sortWeight")
        List<LiveChannel> getFavoriteChannels();

        @Query("SELECT DISTINCT groupName FROM live_channels ORDER BY groupName")
        List<String> getAllGroupNames();

        @Query("SELECT * FROM live_channels WHERE channelName = :name AND groupName = :group LIMIT 1")
        LiveChannel findChannel(String name, String group);

        @Query("SELECT COUNT(*) FROM live_channels")
        int getChannelCount();

        @Query("SELECT COUNT(*) FROM live_channels WHERE groupName = :groupName")
        int getGroupChannelCount(String groupName);

        @Query("UPDATE live_channels SET favorite = :favorite WHERE id = :channelId")
        void setFavorite(long channelId, boolean favorite);

        @Query("UPDATE live_channels SET lastPlayTime = :time WHERE id = :channelId")
        void updateLastPlayTime(long channelId, long time);

        @Query("SELECT * FROM live_channels ORDER BY lastPlayTime DESC LIMIT :limit")
        List<LiveChannel> getRecentChannels(int limit);

        @Query("UPDATE live_channels SET bestSpeed = :speed, lastSpeedTestTime = :time WHERE id = :channelId")
        void updateSpeedInfo(long channelId, double speed, long time);

        @Query("DELETE FROM live_channels WHERE sourceOrigin = :origin")
        void deleteByOrigin(String origin);
    }
}
