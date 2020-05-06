package xie.morrowind.tool.apkextractor;

import android.Manifest;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements Handler.Callback {
    Context context = null;
    Handler handler = null;
    ListView listView = null;
    ListAdapter listAdapter = null;
    Thread loadingThread = null;
    List<App> appList = new ArrayList<>();

    private class App implements Parcelable {
        String pkg;
        Bitmap icon;
        String name;
        String version;
        String sourceDir;
        String destDir;

        private App() {
            super();
        }

        private App(Parcel in) {
            pkg = in.readString();
            icon = in.readParcelable(Bitmap.class.getClassLoader());
            name = in.readString();
            version = in.readString();
            sourceDir = in.readString();
            destDir = in.readString();
        }

        public boolean destExist() {
            File dst = new File(destDir);
            if(!dst.exists()) {
                return false;
            }
            File src = new File(sourceDir);
            return dst.length() == src.length();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(pkg);
            dest.writeParcelable(icon, flags);
            dest.writeString(name);
            dest.writeString(version);
            dest.writeString(sourceDir);
            dest.writeString(destDir);
        }

        public final Parcelable.Creator<App> CREATOR = new Parcelable.Creator<App>() {
            public App createFromParcel(Parcel in) {
                return new App(in);
            }

            public App[] newArray(int size) {
                return new App[size];
            }
        };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        context = this;
        handler = new Handler(this);
        listView = findViewById(R.id.apk_list_view);
        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);
        loadingThread = new LoadingThread();
        loadingThread.start();
        if(ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                ActivityCompat.requestPermissions(this, new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch(msg.what) {
            case 0:
                App app = msg.getData().getParcelable("app");
                appList.add(app);
                listAdapter.notifyDataSetChanged();
                break;
            case 1:
                listAdapter.notifyDataSetChanged();
                break;
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        if(loadingThread != null && loadingThread.isAlive() && !loadingThread.isInterrupted()) {
            loadingThread.interrupt();
        }
        for (App app : appList) {
            app.icon.recycle();
        }
        super.onDestroy();
    }

    private class CopyThread extends Thread {
        private App app;
        public CopyThread(App app) {
            super();
            this.app = app;
        }
        public void run() {
            super.run();
            File src = new File(app.sourceDir);
            File dst = new File(app.destDir);
            if(!src.exists()) {
                return;
            }
            if(dst.exists()) {
                dst.delete();
            }
            try {
                FileInputStream fis = new FileInputStream(src);
                FileOutputStream fos = new FileOutputStream(dst);
                byte[] buf = new byte[1024];
                int n;
                while((n = fis.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
                fis.close();
                fos.close();

            } catch (IOException e) {
                e.printStackTrace();
            }
            handler.sendEmptyMessage(1);
        }
    }

    public void extractApk(View view) {
        int position = (int) view.getTag();
        App app = appList.get(position);
        ProgressBar pb = new ProgressBar(context);
        pb.setMax(100);

        Thread copyThread = new CopyThread(app);
        copyThread.start();
    }

    private Bitmap loadIcon(PackageManager pm, ApplicationInfo ai) {
        Bitmap icon;
        Drawable drawable = ai.loadIcon(pm);
        if(drawable instanceof BitmapDrawable) {
            BitmapDrawable bd = (BitmapDrawable) drawable;
            icon = bd.getBitmap();
        } else if (drawable instanceof LayerDrawable) {
            LayerDrawable ld = (LayerDrawable) drawable;
            icon = ((BitmapDrawable) ld.getDrawable(0)).getBitmap();
        } else/* if (drawable instanceof AdaptiveIconDrawable)*/ {
            icon = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(icon);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
        }
        return icon;
    }

    private class LoadingThread extends Thread {
        @Override
        public void run() {
            super.run();
            PackageManager pm = context.getPackageManager();
            List<PackageInfo> appInfos = pm.getInstalledPackages(0);
            for(PackageInfo pi : appInfos) {
                if(Thread.currentThread().isInterrupted()) {
                    break;
                }
                if((pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    App app = new App();
                    app.pkg = pi.packageName;
                    app.icon = loadIcon(pm, pi.applicationInfo);
                    app.name = pi.applicationInfo.loadLabel(pm).toString();
                    app.version = pi.versionName;
                    app.sourceDir = pi.applicationInfo.sourceDir;
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    String outputName = app.name+"_"+app.version+".apk";
                    app.destDir = new File(dir, outputName).getAbsolutePath();
                    Bundle data = new Bundle();
                    data.putParcelable("app", app);
                    Message msg = handler.obtainMessage(0);
                    msg.setData(data);
                    handler.sendMessage(msg);
                }
            }
        }
    }

    public static Bitmap graylize(Bitmap img) {
        if(img == null) {
            return null;
        }
        int width = img.getWidth();
        int height = img.getHeight();
        int[] pixels = new int[width * height];
        img.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int p = pixels[width * y + x];
                int alpha = (p & 0xFF000000);
                int red = ((p & 0x00FF0000) >> 16);
                int green = ((p & 0x0000FF00) >> 8);
                int blue = (p & 0x000000FF);
                p = (int) (red * 0.3 + green * 0.59 + blue * 0.11);
                p = alpha | (p << 16) | (p << 8) | p;
                pixels[width * y + x] = p;
            }
        }
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        result.setPixels(pixels, 0, width, 0, 0, width, height);
        return result;
    }

    private class ListAdapter extends BaseAdapter {
        LayoutInflater inflater;

        public ListAdapter(Context context) {
            super();
            inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        @Override
        public int getCount() {
            return appList.size();
        }

        @Override
        public App getItem(int position) {
            return appList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return appList.get(position).pkg.hashCode();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if(convertView == null) {
                convertView = inflater.inflate(R.layout.apk_item, parent, false);
            }
            App app = appList.get(position);
            ImageView iconView = convertView.findViewById(R.id.apk_icon);
            if(app.destExist()) {
                iconView.setImageBitmap(app.icon);
            } else {
                Bitmap grayIcon = graylize(app.icon);
                iconView.setImageBitmap(grayIcon);
                //grayIcon.recycle();
            }
            iconView.setTag(position);
            TextView nameView = convertView.findViewById(R.id.apk_name);
            nameView.setText(app.name+" "+app.version);
            TextView pathView = convertView.findViewById(R.id.apk_path);
            pathView.setText(app.sourceDir);
            return convertView;
        }
    }
}
