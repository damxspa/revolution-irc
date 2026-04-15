package io.mrarm.irc.setup;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.webkit.MimeTypeMap;

import net.lingala.zip4j.exception.ZipException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.mrarm.irc.config.BackupManager;
import io.mrarm.irc.R;

public class BackupProgressActivity extends SetupProgressActivity {

    private static final int BACKUP_FILE_REQUEST_CODE = 1000;
    private static final int BACKUP_PASSWORD_REQUEST_CODE = 1001;

    public static final String ARG_USER_PASSWORD = "password";
    public static final String ARG_RESTORE_MODE = "restore_mode";

    public static final int RESTORE_RESULT_OK = 0;
    public static final int RESTORE_RESULT_ERROR = 1;
    public static final int RESTORE_RESULT_INVALID_PASSWORD = 2;

    private boolean mRestoreMode = false;
    private File mBackupFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getIntent().getBooleanExtra(ARG_RESTORE_MODE, false)) {
            mRestoreMode = true;
            setTitle(R.string.title_activity_backup_progress_restore);
            askOpenBackup();
        } else {
            String password = getIntent().getStringExtra(ARG_USER_PASSWORD);
            acquireExitLock();
            runBackupTask(password);
        }
    }

    public void setDone(int resId) {
        releaseExitLock();
        Intent intent = new Intent(BackupProgressActivity.this, BackupCompleteActivity.class);
        intent.putExtra(BackupCompleteActivity.ARG_DESC_TEXT, resId);
        if (mRestoreMode)
            intent.putExtra(BackupCompleteActivity.ARG_RESTORE_MODE, true);
        startNextActivity(intent);
    }

    public void askOpenBackup() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(MimeTypeMap.getSingleton().getMimeTypeFromExtension("zip"));
        startActivityForResult(intent, BACKUP_FILE_REQUEST_CODE);
        setSlideAnimation(false);
    }

    public void onBackupDone(File file) {
        if (file == null) {
            setDone(R.string.error_generic);
            return;
        }
        if (Build.VERSION.SDK_INT >= 19) {
            mBackupFile = file;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(MimeTypeMap.getSingleton().getMimeTypeFromExtension("zip"));
            String date = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
            intent.putExtra(Intent.EXTRA_TITLE, "irc-client-backup-" + date + ".zip");
            startActivityForResult(intent, BACKUP_FILE_REQUEST_CODE);
            setSlideAnimation(false);
        } else {
            // TODO:
        }
    }

    public void onRestoreDone(Integer result) {
        if (result == RESTORE_RESULT_OK) {
            setDone(R.string.backup_restored);
        } else if (result == RESTORE_RESULT_ERROR) {
            setDone(R.string.error_generic);
        } else if (result == RESTORE_RESULT_INVALID_PASSWORD) {
            Intent intent = new Intent(BackupProgressActivity.this, BackupPasswordActivity.class);
            intent.putExtra(BackupPasswordActivity.ARG_RESTORE_MODE, true);
            intent.putExtra(BackupPasswordActivity.ARG_WAS_INVALID, true);
            startActivityForResult(intent, BACKUP_PASSWORD_REQUEST_CODE);
        }
    }

    public void onBackupCopyDone(boolean success) {
        if (success) {
            if (mRestoreMode) {
                if (!BackupManager.verifyBackupFile(mBackupFile)) {
                    mBackupFile.delete();
                    setDone(R.string.backup_restore_invalid);
                } else if (BackupManager.isBackupPasswordProtected(mBackupFile)) {
                    Intent intent = new Intent(BackupProgressActivity.this, BackupPasswordActivity.class);
                    intent.putExtra(BackupPasswordActivity.ARG_RESTORE_MODE, true);
                    startActivityForResult(intent, BACKUP_PASSWORD_REQUEST_CODE);
                } else {
                    startRestoreTask(null);
                }
            } else {
                setDone(R.string.backup_created);
            }
        } else {
            setDone(R.string.error_generic);
        }
    }

    public void startRestoreTask(String password) {
        acquireExitLock();
        runRestoreTask(mBackupFile, password);
    }

    public void cancel() {
        if (mBackupFile != null)
            mBackupFile.delete();
        setDone(mRestoreMode ? R.string.backup_restore_cancelled : R.string.backup_cancelled);
        setSlideAnimation(true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == BACKUP_FILE_REQUEST_CODE) {
            if (data != null && data.getData() != null) {
                try {
                    Uri uri = data.getData();
                    if (mRestoreMode) {
                        ParcelFileDescriptor desc = getContentResolver().openFileDescriptor(uri, "r");
                        mBackupFile = new File(getCacheDir(), "temp-backup.zip");
                        mBackupFile.deleteOnExit();
                        FileInputStream fis = new FileInputStream(desc.getFileDescriptor());
                        FileOutputStream fos = new FileOutputStream(mBackupFile);
                        runCopyFileTask(fis, fos, desc);
                    } else {
                        ParcelFileDescriptor desc = getContentResolver().openFileDescriptor(uri, "w");
                        FileOutputStream fos = new FileOutputStream(desc.getFileDescriptor());
                        FileInputStream fis = new FileInputStream(mBackupFile);
                        runCopyFileTask(fis, fos, desc);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    setDone(R.string.error_generic);
                }
            } else {
                cancel();
            }
        } else if (requestCode == BACKUP_PASSWORD_REQUEST_CODE) {
            if (resultCode == BackupPasswordActivity.RESULT_CODE_PASSWORD) {
                startRestoreTask(data.getStringExtra(BackupPasswordActivity.RET_PASSWORD));
            } else {
                cancel();
            }
        }
    }

    private void runBackupTask(String password) {
        new Thread(() -> {
            Context context = getApplicationContext();
            File backupFile = new File(context.getCacheDir(), "temp-backup.zip");
            if (backupFile.exists())
                backupFile.delete();
            backupFile.deleteOnExit();
            File result;
            try {
                BackupManager.createBackup(context, backupFile, password);
                result = backupFile;
            } catch (IOException e) {
                e.printStackTrace();
                backupFile.delete();
                result = null;
            }
            File finalResult = result;
            runOnUiThread(() -> onBackupDone(finalResult));
        }).start();
    }

    private void runRestoreTask(File file, String password) {
        new Thread(() -> {
            Context context = getApplicationContext();
            int result;
            try {
                BackupManager.restoreBackup(context, file, password);
                file.delete();
                result = RESTORE_RESULT_OK;
            } catch (IOException e) {
                e.printStackTrace();
                if (e.getCause() instanceof ZipException &&
                        ((ZipException) e.getCause()).getType() == ZipException.Type.WRONG_PASSWORD) {
                    result = RESTORE_RESULT_INVALID_PASSWORD;
                } else {
                    file.delete();
                    result = RESTORE_RESULT_ERROR;
                }
            }
            int finalResult = result;
            runOnUiThread(() -> onRestoreDone(finalResult));
        }).start();
    }

    private void runCopyFileTask(FileInputStream fis, FileOutputStream fos, ParcelFileDescriptor fd) {
        new Thread(() -> {
            boolean success = true;
            try {
                byte[] buf = new byte[1024 * 16];
                int c;
                while ((c = fis.read(buf, 0, buf.length)) > 0) {
                    fos.write(buf, 0, c);
                }
                fis.close();
                fos.close();
            } catch (Exception e) {
                e.printStackTrace();
                success = false;
            } finally {
                try {
                    if (fd != null)
                        fd.close();
                } catch (Exception ignored) {
                }
            }
            boolean finalSuccess = success;
            runOnUiThread(() -> onBackupCopyDone(finalSuccess));
        }).start();
    }

}
