package com.ch_linghu.fanfoudroid.helper;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Stack;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.widget.ImageView;

import com.ch_linghu.fanfoudroid.R;
import com.ch_linghu.fanfoudroid.helper.utils.StreamUtils;

/**
 * LazyImageLoader [实验性]
 * 
 * 代码来源: http://stackoverflow.com/questions/541966/android-how-do-i-do-a-lazy-load-of-images-in-listview/3068012#3068012
 * 
 * Usage:
 * -----------------------------
 *  holder.profileImage.setTag(profileImageUrl);
 *  imageLoader.DisplayImage(profileImageUrl, null, holder.profileImage);
 * -----------------------------
 * 
 * TODO: 
 * 在进行以下测试后, 根据结果决定:
 *  - 重构该代码后, 完全替代 ProfileImageCacheManager
 *  - 借鉴其思路改进 ProfileImageCacheManager
 * 
 * 利用已有代码重构:
 *  - 使用目前cache机制(memory+File)后, 重新测试速度
 *  - 使用SoftReference替代HashMap后, 再次测试速度
 *  - 与ImageManager进行兼容, 压缩图片, 写入文件等部分
 * 
 * 主要测试需对比以下方面:
 *  - 速度( 带cache和不带cache, 相同图片多次出现情况 )
 *  - 资源消耗(Thread, memory)
 *
 * LOG:
 * [2011-05-14] 使用原版代码, 略加修改后, 测试基本速度和兼容性 [结果: 较快, 可用]
 * 
 */
public class LazyImageLoader {
    private static final String TAG = "LazyImageLoader";
    
    //the simplest in-memory cache implementation. This should be replaced with something like SoftReference or BitmapOptions.inPurgeable(since 1.6)
    private HashMap<String, Bitmap> cache=new HashMap<String, Bitmap>();
    
    private File cacheDir;
    private Context mContext;
    
    public LazyImageLoader(Context context){
        //Make the background thead low priority. This way it will not affect the UI performance
        photoLoaderThread.setPriority(Thread.NORM_PRIORITY-1);
        mContext = context;
        
        //Find the dir to save cached images
        if (android.os.Environment.getExternalStorageState().equals(android.os.Environment.MEDIA_MOUNTED))
            cacheDir=new File(android.os.Environment.getExternalStorageDirectory(),"LazyList");
        else
            cacheDir=context.getCacheDir();
        if(!cacheDir.exists())
            cacheDir.mkdirs();
    }
    
    final int stub_id = R.drawable.user_default_photo;
    
    public void DisplayImage(String url, Activity activity, ImageView imageView)
    {
        if(cache.containsKey(url))
            imageView.setImageBitmap(cache.get(url));
        else
        {
            queuePhoto(url, activity, imageView);
            imageView.setImageResource(stub_id);
        }    
    }
        
    private void queuePhoto(String url, Activity activity, ImageView imageView)
    {
        //This ImageView may be used for other images before. So there may be some old tasks in the queue. We need to discard them. 
        photosQueue.Clean(imageView);
        PhotoToLoad p=new PhotoToLoad(url, imageView);
        synchronized(photosQueue.photosToLoad){
            photosQueue.photosToLoad.push(p);
            photosQueue.photosToLoad.notifyAll();
        }
        
        //start thread if it's not started yet
        if(photoLoaderThread.getState()==Thread.State.NEW)
            photoLoaderThread.start();
    }
    
    private Bitmap getBitmap(String url) 
    {
        //I identify images by hashcode. Not a perfect solution, good for the demo.
        String filename=String.valueOf(url.hashCode());
        
        FileOutputStream fos;
        try {
            fos = mContext.openFileOutput(filename, Context.MODE_PRIVATE);
        } catch (FileNotFoundException e) {
            Log.w(TAG, "Error creating file.");
            return null;
        }
        
        /*from SD cache
        Bitmap b = decodeFile(f);
        if(b!=null)
            return b;
        */
        
        //from web
        Bitmap bitmap = null;
        try {
            InputStream is=new URL(url).openStream();
            StreamUtils.CopyStream(is, fos);
            fos.close();
            File file = mContext.getFileStreamPath(filename);
            bitmap = BitmapFactory.decodeFile(file.getPath());
            Log.d(TAG, "Writing file: " + filename);
        } catch (Exception ex){
            ex.printStackTrace();
        } finally {
        }
        return bitmap;
    }

    //Task for the queue
    private class PhotoToLoad
    {
        public String url;
        public ImageView imageView;
        public PhotoToLoad(String u, ImageView i){
            url=u; 
            imageView=i;
        }
    }
    
    PhotosQueue photosQueue=new PhotosQueue();
    
    public void stopThread()
    {
        photoLoaderThread.interrupt();
    }
    
    //stores list of photos to download
    class PhotosQueue
    {
        private Stack<PhotoToLoad> photosToLoad=new Stack<PhotoToLoad>();
        
        //removes all instances of this ImageView
        public void Clean(ImageView image)
        {
            for(int j=0 ;j<photosToLoad.size();){
                if(photosToLoad.get(j).imageView==image)
                    photosToLoad.remove(j);
                else
                    ++j;
            }
        }
    }
    
    class PhotosLoader extends Thread {
        public void run() {
            try {
                while(true)
                {
                    //thread waits until there are any images to load in the queue
                    if(photosQueue.photosToLoad.size()==0)
                        synchronized(photosQueue.photosToLoad){
                            photosQueue.photosToLoad.wait();
                        }
                    if(photosQueue.photosToLoad.size()!=0)
                    {
                        PhotoToLoad photoToLoad;
                        synchronized(photosQueue.photosToLoad){
                            photoToLoad=photosQueue.photosToLoad.pop();
                        }
                        Bitmap bmp=getBitmap(photoToLoad.url);
                        cache.put(photoToLoad.url, bmp);
                        Object tag=photoToLoad.imageView.getTag();
                        if(tag!=null && ((String)tag).equals(photoToLoad.url)){
                            BitmapDisplayer bd=new BitmapDisplayer(bmp, photoToLoad.imageView);
                            Activity a=(Activity)photoToLoad.imageView.getContext();
                            a.runOnUiThread(bd);
                        }
                    }
                    if(Thread.interrupted())
                        break;
                }
            } catch (InterruptedException e) {
                //allow thread to exit
            }
        }
    }
    
    PhotosLoader photoLoaderThread=new PhotosLoader();
    
    //Used to display bitmap in the UI thread
    class BitmapDisplayer implements Runnable
    {
        Bitmap bitmap;
        ImageView imageView;
        public BitmapDisplayer(Bitmap b, ImageView i){bitmap=b;imageView=i;}
        public void run()
        {
            if(bitmap!=null)
                imageView.setImageBitmap(bitmap);
            else
                imageView.setImageResource(stub_id);
        }
    }

    public void clearCache() {
        //clear memory cache
        cache.clear();
        
        //clear SD cache
        File[] files=cacheDir.listFiles();
        for(File f:files)
            f.delete();
    }

}