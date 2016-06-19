package mobile.powershare.image_utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import mobile.powershare.R;
import mobile.powershare.services.FileCacheService;
import android.os.Handler;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

/**
 * 
 * A class that adds some sophistication to loading the images for the slides,
 * documents, images and so on. It allows caching for efficiency of the image
 * and storage in the persistent memory of the device to not train the resources
 * of the device. This class should (and has) to only be loaded once per
 * application to aid in this efficiency as the store of the images will work
 * despite changing activities and returning. It also allows a 'lazy loading' of
 * these image when they appear on the screen so that only images that are
 * viewed actually get loaded, making an activity perform much faster.
 * 
 */
public class ImageLoader
{
	public static final int DEFAULT = R.drawable.is_default;
	
	public static final int DOCUMENT = R.drawable.is_document;
	public static final int DOCUMNET_LIST = R.drawable.is_document_list;
	public static final int DOCUMENT_LIST_LARGE = R.drawable.is_document_list_large;

	public static final int SLIDE_LIST = R.drawable.is_slide_list;
	public static final int SLIDE_LIST_HORIZONTAL = R.drawable.is_slide_list_horizontal;
	public static final int SLIDE_LIST_HORIZONTAL_LARGE = R.drawable.is_slide_list_horizontal_large;

	public static final int IMAGE_LIST = R.drawable.is_image_list;

	public static final int PRESENTATION = R.drawable.is_presentation;
	public static final int PRESENTATION_LARGE = R.drawable.is_presentation_large;

	// //

	private ImageMemoryCache imageMemoryCache = new ImageMemoryCache();
	private FileCacheService fileCache;

	private Map<ImageView, String> imageViews = Collections.synchronizedMap(new WeakHashMap<ImageView, String>());
	private ExecutorService executorService;
	private Handler handler = new Handler();// handler to display images in UI
											// thread

	public ImageLoader()
	{
		fileCache = new FileCacheService("PowershareImageTemp");
		executorService = Executors.newFixedThreadPool(5);
	}

	private int stub_id = 0;

	public Drawable getImageDrawable(String url)
	{
		try
		{
			InputStream is = (InputStream) new URL(url).getContent();
			Drawable d = Drawable.createFromStream(is, "src name");
			return d;
		}

		catch (Exception e)
		{
			System.out.println("Exc=" + e);
			return null;
		}
	}

	public void DisplayImage(String url, ImageView imageView, int imageStubType)
	{
		stub_id = imageStubType;

		int smallestSideRequiredSize = getRequiredSize(imageStubType);

		imageViews.put(imageView, url);
		Bitmap bitmap = imageMemoryCache.get(url);

		if (bitmap != null)
			imageView.setImageBitmap(bitmap);
		else
		{
			queueImage(url, imageView, smallestSideRequiredSize);
			imageView.setImageResource(stub_id);
		}
	}

	private int getRequiredSize(int imageStubSize)
	{
		// returns smallest side of image for the required size (for scrolling
		// purposes). The image size is important to not waste memory resources
		// while at the same time keeping the quality of the image acceptable

		int smallestSideRequiredSize = 0;

		switch (stub_id)
		{
			case (DOCUMENT):
				smallestSideRequiredSize = 230;
				break;				
			case (DOCUMNET_LIST):
				smallestSideRequiredSize = 74;
				break;
			case (DOCUMENT_LIST_LARGE):
				smallestSideRequiredSize = 350;
				break;
			case (SLIDE_LIST):
				smallestSideRequiredSize = 103;
				break;
			case (SLIDE_LIST_HORIZONTAL):
				smallestSideRequiredSize = 146;
				break;
			case (SLIDE_LIST_HORIZONTAL_LARGE):
				smallestSideRequiredSize = 212;
				break;
			case (IMAGE_LIST):
				smallestSideRequiredSize = 120;
				break;
			case (PRESENTATION):
				smallestSideRequiredSize = 230;
				break;
			case (PRESENTATION_LARGE):
				smallestSideRequiredSize = 215;
				break;
			case (DEFAULT):
				smallestSideRequiredSize = 100;
				break;
			default:
				smallestSideRequiredSize = 350;
				break;
		}

		return smallestSideRequiredSize;
	}

	private void queueImage(String url, ImageView imageView, int smallestSideRequiredSize)
	{
		ImageToLoad p = new ImageToLoad(url, imageView);
		executorService.submit(new ImagesLoader(p, smallestSideRequiredSize));
	}

	private Bitmap getBitmap(String url, int smallestSideRequiredSize)
	{
		File f = fileCache.getFile(url);

		// from SD cache
		Bitmap b = decodeFile(f, smallestSideRequiredSize);

		if (b != null)
			return b;

		// from web
		try
		{
			Bitmap bitmap = null;
			URL imageUrl = new URL(url);
			HttpURLConnection conn = (HttpURLConnection) imageUrl.openConnection();
			conn.setConnectTimeout(30000);
			conn.setReadTimeout(30000);
			conn.setInstanceFollowRedirects(true);
			InputStream is = conn.getInputStream();
			OutputStream os = new FileOutputStream(f);
			CopyStream(is, os);
			os.close();
			conn.disconnect();
			bitmap = decodeFile(f, smallestSideRequiredSize);

			return bitmap;
		}
		catch (Throwable ex)
		{
			ex.printStackTrace();

			if (ex instanceof OutOfMemoryError)
				imageMemoryCache.clear();

			return null;
		}
	}

	private static void CopyStream(InputStream is, OutputStream os)
	{
		final int buffer_size = 1024;

		try
		{
			byte[] bytes = new byte[buffer_size];

			for (;;)
			{
				int count = is.read(bytes, 0, buffer_size);

				if (count == -1)
					break;

				os.write(bytes, 0, count);
			}
		}
		catch (Exception ex)
		{}
	}

	// decodes image and scales it to reduce memory consumption
	private Bitmap decodeFile(File f, int smallestSideRequiredSize)
	{
		try
		{
			// decode image size
			BitmapFactory.Options o = new BitmapFactory.Options();
			o.inJustDecodeBounds = true;
			FileInputStream stream1 = new FileInputStream(f);
			BitmapFactory.decodeStream(stream1, null, o);
			stream1.close();

			int width_tmp = o.outWidth, height_tmp = o.outHeight;
			int scale = 1;

			while (true)
			{
				if (width_tmp / 2 < smallestSideRequiredSize || height_tmp / 2 < smallestSideRequiredSize)
					break;

				width_tmp /= 2;
				height_tmp /= 2;
				scale *= 2;
			}

			// decode with inSampleSize
			BitmapFactory.Options o2 = new BitmapFactory.Options();
			o2.inSampleSize = scale;
			FileInputStream stream2 = new FileInputStream(f);
			Bitmap bitmap = BitmapFactory.decodeStream(stream2, null, o2);
			stream2.close();

			return bitmap;
		}
		catch (FileNotFoundException e)
		{}
		catch (IOException e)
		{
			e.printStackTrace();
		}
		return null;
	}

	// Task for the queue
	private class ImageToLoad
	{
		public String url;
		public ImageView imageView;

		public ImageToLoad(String u, ImageView i)
		{
			url = u;
			imageView = i;
		}
	}

	class ImagesLoader implements Runnable
	{
		ImageToLoad imageToLoad;
		int smallestSideRequiredSize;

		ImagesLoader(ImageToLoad imageToLoad, int smallestSideRequiredSize)
		{
			this.imageToLoad = imageToLoad;
			this.smallestSideRequiredSize = smallestSideRequiredSize;
		}

		@Override
		public void run()
		{
			try
			{
				if (imageViewReused(imageToLoad))
					return;

				Bitmap bmp = getBitmap(imageToLoad.url, smallestSideRequiredSize);
				imageMemoryCache.put(imageToLoad.url, bmp);

				if (imageViewReused(imageToLoad))
					return;

				BitmapDisplayer bd = new BitmapDisplayer(bmp, imageToLoad);
				handler.post(bd);
			}
			catch (Throwable th)
			{
				th.printStackTrace();
			}
		}
	}

	boolean imageViewReused(ImageToLoad imageToLoad)
	{
		String tag = imageViews.get(imageToLoad.imageView);

		if (tag == null || !tag.equals(imageToLoad.url))
			return true;

		return false;
	}

	// Used to display bitmap in the UI thread
	class BitmapDisplayer implements Runnable
	{
		Bitmap bitmap;
		ImageToLoad imageToLoad;

		public BitmapDisplayer(Bitmap b, ImageToLoad p)
		{
			bitmap = b;
			imageToLoad = p;
		}

		public void run()
		{
			if (imageViewReused(imageToLoad))
				return;

			if (bitmap != null)
				imageToLoad.imageView.setImageBitmap(bitmap);
			else
				imageToLoad.imageView.setImageResource(stub_id);
		}
	}

	public void clearCache()
	{
		imageMemoryCache.clear();
		fileCache.clear();
	}
}