package com.qr.decode;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.Camera;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.view.Surface;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.qr.ICameraP;
import com.qr.LuminanceLevel;
import com.qr.QResult;
import com.qr.camera.CameraManager;
import com.qr.decode.base.QDecode;
import com.qr.util.BitmapUtils;
import com.qr.util.ZXingUtils;

import net.sourceforge.zbar.Config;
import net.sourceforge.zbar.Image;
import net.sourceforge.zbar.ImageScanner;
import net.sourceforge.zbar.Symbol;
import net.sourceforge.zbar.SymbolSet;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Author：SLTPAYA
 * Date：2017/12/6 15:25
 */
@SuppressWarnings("SuspiciousNameCombination")
public class AllDecode extends QDecode {

    private Handler mHandler;
    private Camera.Size mSize;
    private ImageScanner mImageScanner;
    private MultiFormatReader multiFormatReader;

    private Rect mRect;
    private byte[] mData;
    private Image barcode;
    private boolean isInitData = false;
    private ExecutorService executorService = Executors.newSingleThreadExecutor();

    @Override
    public void init(Handler handler, ICameraP i) {
        super.init(handler, i);
        mHandler = handler;
        initComponents();
    }

    /**
     * 初始化组扫描组件
     */
    private void initComponents() {
        //zbar
        mImageScanner = new ImageScanner();
        mImageScanner.setConfig(Config.ENABLE, Config.X_DENSITY, 1);
        mImageScanner.setConfig(Config.ENABLE, Config.Y_DENSITY, 1);
        mImageScanner.enableCache(true);

        //zxing
        multiFormatReader = new MultiFormatReader();
        multiFormatReader.setHints(ZXingUtils.setMode(ZXingUtils.ALL_MODE));
    }

    private byte[] mDataa = new byte[1];

    private boolean isHasData = false;

    @Override
    public void decode(byte[] data, Camera.Size size, int width, int height, ICameraP i) {
        QDecode.needDecode = false;

        CameraManager cameraManager = i.getCameraManager();
        //Size surfaceSize = cameraManager.getConfigManager().getSurfaceSize();
        Point cameraResolution = cameraManager.getCameraResolution();//1920 1080

//        if (!isHasData) {
//            if (data.length > 0) {
//                isHasData = true;
//                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
//                SaveImg.saveImg(bitmap, System.currentTimeMillis() + ".jpg", i.getCameraManager().getContext());
//            }
//        }

        if (!isInitData) {
            isInitData = true;

            //方案2
//            Size previewSize = cameraManager.getSurfaceSize();
//
//            RectF rectF = processRect(previewSize, surfaceSize, cameraManager.getConfigManager().getOrientation());
//
//            rectF.height();
//            rectF.width();
//
//            if (!rectF.isEmpty()) {
//                int frameLeft = (int) (rectF.left * width);
//                int frameTop = (int) (rectF.top * height);
//                int frameWidth = (int) (rectF.width() * width);
//                int frameHeight = (int) (rectF.height() * height);
//                mRect = new Rect(frameLeft, frameTop, frameLeft + frameWidth, frameTop + frameHeight);
//            }

            //方案1
            Point screenResolution = cameraManager.getScreenResolution();//1080 1808

            RectF processRect = processRect(Size.toSize(screenResolution), Size.toSize(cameraResolution), cameraManager.getConfigManager().getOrientation());

            if (!processRect.isEmpty()) {
                int frameLeft = (int) (processRect.left * width);
                int frameTop = (int) (processRect.top * height);
                int frameWidth = (int) (processRect.width() * width);
                int frameHeight = (int) (processRect.height() * height);
                mRect = new Rect(frameLeft, frameTop, frameLeft + frameWidth, frameTop + frameHeight);
            }
        }
        mSize = size;
        mData = data;

        /*构建解码图像*/
        barcode = new Image(width, height, Image.TYPE_Y800);
        barcode.setData(mData);
        //1920*1080
        //barcode.setSize(surfaceSize.width, surfaceSize.height);
        barcode.setSize(cameraResolution.x, cameraResolution.y);
        //[注意： 摄像头取得是横屏数据， 取景框位置是相对于竖直屏幕位置而言， 所以把left-->top, top-->left， 此处取景框的宽高一致，无所谓]
        barcode.setCrop(mRect);

        executorService.execute(mAnalysisTask);
    }

    private RectF processRect(Size previewSize, Size surfaceSize, int orientation) {
        int previewWidth = previewSize.width;
        int previewHeight = previewSize.height;

        int surfaceWidth = surfaceSize.width;
        int surfaceHeight = surfaceSize.height;

        Rect rect = i.getCropRect();
        RectF rectF = new RectF(rect.left, rect.top, rect.right, rect.bottom);
        int frameLeft = rect.left;
        int frameTop = rect.top;
        int frameRight = rect.right;
        int frameBottom = rect.bottom;
        if (frameLeft >= frameRight || frameTop >= frameBottom) {
            rectF.setEmpty();
            return rectF;
        }
        //交换宽高
        if (orientation % 2 == 0) {
            int temp = surfaceWidth;
            surfaceWidth = surfaceHeight;
            surfaceHeight = temp;
        }
        float ratio;//图像帧的缩放比，比如1000*2000像素的图像显示在100*200的View上，缩放比就是10
        if (previewWidth * surfaceHeight < surfaceWidth * previewHeight) {//图像帧的宽超出了View的左边，以高计算缩放比例
            ratio = 1F * surfaceHeight / previewHeight;
        } else {//图像帧的高超出了View的底边，以宽计算缩放比例
            ratio = 1F * surfaceWidth / previewWidth;
        }
        float leftRatio = Math.max(0, Math.min(1, ratio * frameLeft / surfaceWidth));//计算扫描框的左边在图像帧中所处的位置
        float rightRatio = Math.max(0, Math.min(1, ratio * frameRight / surfaceWidth));//计算扫描框的右边在图像帧中所处的位置
        float topRatio = Math.max(0, Math.min(1, ratio * frameTop / surfaceHeight));//计算扫描框的顶边在图像帧中所处的位置
        float bottomRatio = Math.max(0, Math.min(1, ratio * frameBottom / surfaceHeight));//计算扫描框的底边在图像帧中所处的位置
        switch (orientation) {//根据旋转角度对位置进行校正
            case Surface.ROTATION_0: {
                rectF.set(topRatio, 1 - rightRatio, bottomRatio, 1 - leftRatio);
                break;
            }
            case Surface.ROTATION_90: {
                rectF.set(leftRatio, topRatio, rightRatio, bottomRatio);
                break;
            }
            case Surface.ROTATION_180: {
                rectF.set(1 - bottomRatio, leftRatio, 1 - topRatio, rightRatio);
                break;
            }
            case Surface.ROTATION_270: {
                rectF.set(1 - rightRatio, 1 - bottomRatio, 1 - leftRatio, 1 - topRatio);
                break;
            }
        }
        return rectF;
    }

    private boolean isLongTimeLose = false;
    private long lastZxingDecodeTime = 0;
    private static final int ZXING_OVER_TIME = 700;//解码耗时超过700ms, 视为cpu处理能力较弱
    private static final long ZXING_DECODE_INTER = 2000L;//zxing解码间隔, 在长CPU下

    private Runnable mAnalysisTask = new Runnable() {
        @Override
        public void run() {
            String resultStr = null;

            /*zbar*/
            resultStr = decodeWithZBar();

            if (!TextUtils.isEmpty(resultStr)) {
                System.out.println("长生图片了"+mData.length);
//                Bitmap bitmap = BitmapFactory.decodeByteArray(mData, 0, mData.length);

//                System.out.println("bbbb" + bitmap);

//                SaveImg.saveImg(mData, System.currentTimeMillis() + ".jpg", i.getCameraManager().getContext());
            }

            /*判断是否需要使用zxing*/
            if (TextUtils.isEmpty(resultStr)) {
                long time = System.currentTimeMillis();

                if (isLongTimeLose) {//cpu能力过低, 开启{ZXING_DECODE_INTER}s间隔解码(zing)
                    if (time - lastZxingDecodeTime > ZXING_DECODE_INTER) {
                        //可以解码
                        lastZxingDecodeTime = time;
                        resultStr = decodeWithZxing();
                    }
                } else {//cpu能力足够的, 持续解码
                    resultStr = decodeWithZxing();
                }

                long lose = System.currentTimeMillis() - time;
                if (lose - ZXING_OVER_TIME > 0 && !isLongTimeLose) {
                    //解码时间太长
                    isLongTimeLose = true;
                }
            }

            /*处理亮度*/
            int bright = BitmapUtils.getLuminanceByThumbnail(mData, mRect, mSize.width);
            boolean handleLuminance = handleLuminance(bright);

            /*亮度消息处理*/
            if (handleLuminance) {
                mHandler.obtainMessage(1, bright).sendToTarget();
            }

            /*结果消息处理*/
            if (!TextUtils.isEmpty(resultStr)) {
                QResult qResult = new QResult(resultStr);
                Message message = mHandler.obtainMessage(0, qResult);
                message.sendToTarget();
            } else {
                QDecode.needDecode = true;
            }
        }
    };

    /**
     * 使用zbar解码
     *
     * @return result
     */
    private String decodeWithZBar() {
        String result = null;
        if (mImageScanner.scanImage(barcode) != 0) {
            SymbolSet symSet = mImageScanner.getResults();
            for (Symbol sym : symSet) {
                if (sym.getType() != Symbol.DATABAR) {
                    result = sym.getData();
                }
            }
        }
        return result;
    }

    /**
     * 使用zxing解码
     *
     * @return result
     */
    private String decodeWithZxing() {
        //zxing
        try {
            PlanarYUVLuminanceSource source = buildLuminanceSource(mData, mSize.width, mSize.height);
            if (source != null) {
//                int[] ints = source.renderThumbnail();
//
//                Bitmap bitmap2 = Bitmap.createBitmap(ints, source.getThumbnailWidth(), source.getThumbnailHeight(), Bitmap.Config.ALPHA_8);
//
//                System.out.println("生成了图片");
//                SaveImg.saveImg(bitmap2, System.currentTimeMillis() + ".jpg", i.getCameraManager().getContext());

                BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
                Result rawResult = multiFormatReader.decodeWithState(bitmap);
                return rawResult == null ? null : rawResult.getText();
            }
        } catch (ReaderException re) {
            // continue
        } catch (IllegalArgumentException e) {
            // continue (Sometime. like source.width < 1 and source.height < 1)
        } catch (Exception e) {
            // unknown exception
        } finally {
            multiFormatReader.reset();
        }
        return null;
    }

    private LuminanceLevel lastLevel = null;

    /**
     * 处理亮度值是否改变
     *
     * @param luminance int value
     * @return need send luminance message
     */
    private boolean handleLuminance(int luminance) {
        LuminanceLevel nowLevel;
        if (luminance >= 100) {
            nowLevel = LuminanceLevel.ACME;
        } else if (luminance > 60) {
            nowLevel = LuminanceLevel.HIGH;
        } else if (luminance > 30) {
            nowLevel = LuminanceLevel.MIDDLE;
        } else {
            nowLevel = LuminanceLevel.LOW;
        }

        if (lastLevel == null) {
            lastLevel = nowLevel;
            return false;
        }

        if (nowLevel != lastLevel) {
            //需要发送消息
            lastLevel = nowLevel;
            return true;
        }
        return false;
    }

    private PlanarYUVLuminanceSource buildLuminanceSource(byte[] data, int width, int height) {
        Rect rect = mRect;
        if (rect == null) {
            return null;
        }
        // Go ahead and assume it's YUV rather than die.
        return new PlanarYUVLuminanceSource(data, width, height, rect.top, rect.left, rect.width(), rect
                .height(), false);
    }

}
