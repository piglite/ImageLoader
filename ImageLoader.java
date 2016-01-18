package com.example.musicclient.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;

import com.example.musicclient.util.DiskLruCache.Editor;
import com.example.musicclient.util.DiskLruCache.Snapshot;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory.Options;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.v4.util.LruCache;
import android.util.Log;
import android.widget.ImageView;

public class ImageLoader {

	private static final int ValueCount = 1; //磁盘缓存中一个key对应的value的数量
	private static final long MaxSize = 1*10*1024*1024;//磁盘缓存的容量

	private static final int THREAD_COUNT = CommonUtil.getNumberCores();//线程池中线程的数量（与使用者手机的CPU核心数一致）

	private static LruCache<String,Bitmap> MemoryCache; //内存缓存
	private static DiskLruCache DiskCache ; //磁盘缓存

	private static LinkedBlockingDeque<Runnable> tasks; //任务队列

	private static ExecutorService exec; //线程池

	private static Thread pollThread; //轮询线程

	private static Handler pollHandler; // 执行轮询线程获得的任务

	private static Handler uiHandler; // 将结果呈现到ImageView中

	private static Semaphore taskLock; //保证线程池中不会缓存任务

	private static Semaphore createLock; //保证pollHandler创建要在使用pollHandler使用之前

	private static Context context; //上下文

	private static ImageLoader loader = new ImageLoader(); //为了实现init后的管道式调用，所以创建这个对象

	private static boolean isFirstTime = true; //防止每次调用init出现重复初始化

	public static ImageLoader init(Context c){
		
		if(!isFirstTime){//isFirstTime为false时直接返回loader对象
			return loader;
		}

		taskLock = new Semaphore(THREAD_COUNT); 
		createLock = new Semaphore(0);
		context = c;
		//初始化LruCache
		int size = (int) Runtime.getRuntime().maxMemory(); 
		if(MemoryCache==null)
			MemoryCache	= new LruCache<String, Bitmap>(size/4){
			protected int sizeOf(String key, Bitmap value) {
				return value.getByteCount();
			}
		};
		//初始化DiskLruCache
		if(DiskCache==null){
			try {
				DiskCache = DiskLruCache.open(getCacheDir(context,"imageloadercache"), getAppVersion(context), ValueCount, MaxSize);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		//新建下载任务队列
		tasks = new LinkedBlockingDeque<Runnable>();
		//执行下载的线程池
		exec = Executors.newFixedThreadPool(THREAD_COUNT);
		//主线程Handler，将下载的结果提交到主线程
		uiHandler = new Handler(Looper.getMainLooper()){
			public void handleMessage(Message msg) {
				TaskBean bean = (TaskBean)msg.obj;
				ImageView iv = bean.imageview;
				String uri = bean.uri;
				if(iv.getTag().toString().equals(uri)){
					iv.setImageBitmap(bean.result);
				}
			};
		};
		//轮询线程，从任务队列中取任务
		pollThread = new Thread("pollThread"){
			public void run() {
				Looper.prepare();
				pollHandler = new Handler(){
					public void handleMessage(Message msg) {
						try {
							//采用LIFO原则，优先取出后添加的任务放到池中执行
							exec.execute(tasks.takeLast());
							taskLock.acquire();
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
				};
				//当且仅当pollHandler穿件完毕后，才可以通过pollHandler向pollThread中添加Message
				createLock.release();
				Looper.loop();
			};
		};
		pollThread.start();
		//初始化完毕
		isFirstTime = false;
		
		return loader;
	}

	public static void loadImage(final ImageView imageView, final String s){
		final String uri = getMD5(s);
		imageView.setTag(uri);
		Bitmap bm = MemoryCache.get(uri);
		if(bm!=null){
			Log.d("TAG", "图片从内存缓存加载了");
			imageView.setImageBitmap(bm);
			return;
		}
		try {
			Snapshot snap = DiskCache.get(uri);
			if(snap!=null){
				Log.d("TAG", "图片从SD卡加载");
				InputStream is = snap.getInputStream(0);
				Bitmap bitmap = BitmapFactory.decodeStream(is);
				Log.d("TAG", uri+"/"+snap);
				if(MemoryCache.get(uri)==null)
					MemoryCache.put(uri, bitmap);
				imageView.setImageBitmap(bitmap);
				return;
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		tasks.add(new Runnable() {

			@Override
			public void run() {
				HttpURLConnection connection = null;
				BufferedInputStream in = null;
				BufferedOutputStream out = null;
				try {
					connection = (HttpURLConnection) new URL(s).openConnection();
					connection.connect();
					InputStream is = connection.getInputStream();
					in = new BufferedInputStream(is);
					Bitmap result = compress(imageView, in);
					MemoryCache.put(uri, result);
					Editor editor = DiskCache.edit(uri);
					result.compress(CompressFormat.JPEG, 100, editor.newOutputStream(0));
					editor.commit();
					DiskCache.flush();
					TaskBean bean = new TaskBean(imageView, uri, result);
					Message.obtain(uiHandler, 102, bean).sendToTarget();
					taskLock.release();
				} catch (MalformedURLException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}finally{
					if(connection!=null){
						connection.disconnect();
					}
					if(in != null){
						try {
							in.close();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
					if(out != null){
						try {
							out.close();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}


			}

			private Bitmap compress(final ImageView imageView, InputStream in) {
				Bitmap result = null;
				try {
					ByteArrayOutputStream out = new ByteArrayOutputStream();
					int b = -1;
					while((b=in.read())!=-1){
						out.write(b);
					}

					in.close();
					out.close();
					byte[] bytes = out.toByteArray();
					Options  opts = new Options();
					opts.inJustDecodeBounds = true;
					BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
					int bitmapWidth = opts.outWidth;
					int bitmapHeight = opts.outHeight;
					int width = imageView.getWidth();
					if(width<=0){
						width = CommonUtil.getWindowWidth(context);
					}
					int height = imageView.getHeight();
					if(height<=0){
						height = CommonUtil.getWindowHeight(context);
					}
					int sampleSize = 1;
					if(bitmapWidth*1.0/width>=1||bitmapHeight*1.0/height>=1){
						sampleSize = (int) Math.max(Math.ceil(bitmapWidth*1.0/width), Math.ceil(bitmapHeight*1.0/height));
					}
					opts.inJustDecodeBounds = false;
					opts.inSampleSize = sampleSize;
					result = BitmapFactory.decodeByteArray(bytes,0,bytes.length,opts);
				} catch (IOException e) {
					e.printStackTrace();
				}
				return result;
			}
		});

		if(pollHandler==null){
			try {
				createLock.acquire();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		pollHandler.sendEmptyMessage(101);
	}



	private static int getAppVersion(Context context) {
		try {
			PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
			return info.versionCode;
		} catch (NameNotFoundException e) {
			e.printStackTrace();
		}
		return 1;
	}

	private static File getCacheDir(Context context, String path) {
		File file=context.getExternalCacheDir();
		//		if(Environment.getExternalStorageState()==Environment.MEDIA_MOUNTED){
		//			file = context.getExternalCacheDir();
		//		}else{
		//			file = context.getCacheDir();
		//		}
		File f = new File(file,path);
		return f ;
	}
	private static String getMD5(String uri) {
		String key = null;
		try {
			MessageDigest md = MessageDigest.getInstance("md5");
			md.update(uri.getBytes());
			key  = byteToString(md.digest());
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}
		return key;
	}

	private static String byteToString(byte[] bytes) {
		StringBuffer sb = new StringBuffer();
		for (byte b : bytes) {
			String s = Integer.toHexString(0xFF & b);
			sb.append(s);
		}
		return sb.toString();
	}

	private static class TaskBean{
		ImageView imageview;
		String uri;
		Bitmap result;

		public TaskBean(ImageView imageview, String uri, Bitmap result) {
			super();
			this.imageview = imageview;
			this.uri = uri;
			this.result = result;
		}

	}

}
