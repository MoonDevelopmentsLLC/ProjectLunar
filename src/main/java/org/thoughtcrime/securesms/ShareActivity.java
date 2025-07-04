/*
 * Copyright (C) 2014-2017 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.thoughtcrime.securesms;

import static org.thoughtcrime.securesms.util.RelayUtil.setSharedText;
import static org.thoughtcrime.securesms.util.RelayUtil.setSharedSubject;
import static org.thoughtcrime.securesms.util.RelayUtil.setSharedHtml;
import static org.thoughtcrime.securesms.util.RelayUtil.setSharedTitle;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;

import com.b44t.messenger.DcContext;

import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.mms.PartAuthority;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme;
import org.thoughtcrime.securesms.util.MailtoUtil;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.thoughtcrime.securesms.util.RelayUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * An activity to quickly share content with chats
 *
 * @author Jake McGinty
 */
public class ShareActivity extends PassphraseRequiredActionBarActivity implements ResolveMediaTask.OnMediaResolvedListener
{
  private static final String TAG = ShareActivity.class.getSimpleName();

  public static final String EXTRA_ACC_ID = "acc_id";
  public static final String EXTRA_CHAT_ID = "chat_id";
  public static final String EXTRA_MSG_TYPE = "msg_type";
  public static final String EXTRA_MSG_SUBJECT = "msg_subject";
  public static final String EXTRA_MSG_HTML = "msg_html";
  public static final String EXTRA_TITLE = "extra_title";

  private ArrayList<Uri>               resolvedExtras;
  private DcContext                    dcContext;
  private boolean                      isResolvingUrisOnMainThread;

  @Override
  protected void onPreCreate() {
    dynamicTheme = new DynamicNoActionBarTheme();
    super.onPreCreate();
  }

  @Override
  protected void onCreate(Bundle icicle, boolean ready) {
    dcContext = DcHelper.getContext(this);

    setContentView(R.layout.share_activity);

    initializeToolbar();
    initializeMedia();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    Log.w(TAG, "onNewIntent()");
    super.onNewIntent(intent);
    setIntent(intent);
    initializeMedia();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    ResolveMediaTask.cancelTasks();
  }

  private void initializeToolbar() {
    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    ActionBar actionBar = getSupportActionBar();

    if (actionBar != null) {
      actionBar.setDisplayHomeAsUpEnabled(true);
    }
  }

  private void initializeMedia() {
    resolvedExtras = new ArrayList<>();

    List<Uri> streamExtras = new ArrayList<>();
    if (Intent.ACTION_SEND.equals(getIntent().getAction()) &&
            getIntent().getParcelableExtra(Intent.EXTRA_STREAM) != null) {
        Uri uri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
        streamExtras.add(uri);
    } else if (getIntent().getParcelableArrayListExtra(Intent.EXTRA_STREAM) != null) {
      streamExtras = getIntent().getParcelableArrayListExtra(Intent.EXTRA_STREAM);
    } else {
        Uri uri = getIntent().getData();
        if (MailtoUtil.isMailto(uri)) {
            String[] extraEmail = getIntent().getStringArrayExtra(Intent.EXTRA_EMAIL);
            if (extraEmail == null || extraEmail.length == 0) {
                getIntent().putExtra(Intent.EXTRA_EMAIL, MailtoUtil.getRecipients(uri));
            }
            String text = getIntent().getStringExtra(Intent.EXTRA_TEXT);
            if (text == null || text.isEmpty()) {
                getIntent().putExtra(Intent.EXTRA_TEXT, MailtoUtil.getText(uri));
            }
        } else if (uri != null) {
            streamExtras.add(uri);
        }
    }

    if (needsFilePermission(streamExtras)) {
      if (Permissions.hasAll(this, Manifest.permission.READ_EXTERNAL_STORAGE)) {
        resolveUris(streamExtras);
      } else {
        requestPermissionForFiles(streamExtras);
      }
    } else {
      resolveUris(streamExtras);
    }
  }

  private boolean needsFilePermission(List<Uri> uris) {
    for(Uri uri : uris) {
      // uri may be null, however, hasFileScheme() just returns false in this case
      if (hasFileScheme(uri)) {
        return true;
      }
    }
    return false;
  }

  private void requestPermissionForFiles(List<Uri> streamExtras) {
    Permissions.with(this)
            .request(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
            .alwaysGrantOnSdk33()
            .ifNecessary()
            .withPermanentDenialDialog(getString(R.string.perm_explain_access_to_storage_denied))
            .onAllGranted(() -> resolveUris(streamExtras))
            .onAnyDenied(this::abortShare)
            .execute();
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
  }

  private void abortShare() {
    Toast.makeText(this, R.string.share_abort, Toast.LENGTH_LONG).show();
    finish();
  }

  private void resolveUris(List<Uri> streamExtras) {
    isResolvingUrisOnMainThread = true;
    for (Uri streamExtra : streamExtras) {
      if (streamExtra != null && PartAuthority.isLocalUri(streamExtra)) {
        resolvedExtras.add(streamExtra);
      } else {
        new ResolveMediaTask(this, this).execute(streamExtra);
      }
    }

    if (!ResolveMediaTask.isExecuting()) {
      handleResolvedMedia(getIntent());
    }
    isResolvingUrisOnMainThread = false;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);
    switch (item.getItemId()) {
    case android.R.id.home:     finish();                return true;
    }
    return false;
  }

  @Override
  public void onMediaResolved(Uri uri) {
    if (uri != null) {
      resolvedExtras.add(uri);
    }

    if (!ResolveMediaTask.isExecuting() && !isResolvingUrisOnMainThread) {
     handleResolvedMedia(getIntent());
    }
  }

  private void handleResolvedMedia(Intent intent) {
    int       accId            = intent.getIntExtra(EXTRA_ACC_ID, -1);
    int       chatId           = intent.getIntExtra(EXTRA_CHAT_ID, -1);
    String    msgType          = intent.getStringExtra(EXTRA_MSG_TYPE);

    String[] extraEmail = getIntent().getStringArrayExtra(Intent.EXTRA_EMAIL);
    /*
    usually, external app will try to start "e-mail sharing" intent, providing it:
    1. address(s), packed in array, marked as Intent.EXTRA_EMAIL - mandatory
    2. shared content (files, pics, video), packed in Intent.EXTRA_STREAM - optional

    here is a sample code to trigger this routine from within external app:

    try {
      Intent emailIntent = new Intent(Intent.ACTION_SENDTO, Uri.fromParts(
              "mailto", "someone@example.com", null));
      File f = new File(getFilesDir() + "/somebinaryfile.bin");
      f.createNewFile();
      f.setReadable(true, false);
      byte[] b = new byte[1024];
      new Random().nextBytes(b);
      FileOutputStream fOut = new FileOutputStream(f);
      DataOutputStream dataStream = new DataOutputStream(fOut);
      dataStream.write(b);
      dataStream.close();

      Uri sharedURI = FileProvider.getUriForFile(context, context.getApplicationContext().getPackageName() + ".provider", f);
      emailIntent.setAction(Intent.ACTION_SEND);
      emailIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{"someone@example.com"});
      emailIntent.setType("text/plain");
      emailIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      emailIntent.putExtra(Intent.EXTRA_STREAM, sharedURI);

      // to EXPLICITLY fire DC's sharing activity:
      // emailIntent.setComponent(new ComponentName("com.b44t.messenger.beta", "org.thoughtcrime.securesms.ShareActivity"));

      startActivity(emailIntent);
    } catch (IOException e) {
      e.printStackTrace();
    }catch (ActivityNotFoundException e) {
    }
  */

    if(chatId == -1 && extraEmail != null && extraEmail.length > 0) {
      final String addr = extraEmail[0];
      int contactId = dcContext.lookupContactIdByAddr(addr);

      if(contactId == 0) {
        contactId = dcContext.createContact(null, addr);
      }

      chatId = dcContext.createChatByContactId(contactId);
    }
    Intent composeIntent;
    if (accId != -1 && chatId != -1) {
      composeIntent = getBaseShareIntent(ConversationActivity.class);
      composeIntent.putExtra(ConversationActivity.CHAT_ID_EXTRA, chatId);
      composeIntent.putExtra(ConversationActivity.ACCOUNT_ID_EXTRA, accId);
      RelayUtil.setSharedType(composeIntent, msgType);
      RelayUtil.setSharedUris(composeIntent, resolvedExtras);
      startActivity(composeIntent);
    } else {
      composeIntent = getBaseShareIntent(ConversationListRelayingActivity.class);
      RelayUtil.setSharedType(composeIntent, msgType);
      RelayUtil.setSharedUris(composeIntent, resolvedExtras);
      ConversationListRelayingActivity.start(this, composeIntent);
    }
    finish();
  }

  private Intent getBaseShareIntent(final @NonNull Class<?> target) {
    final Intent intent = new Intent(this, target);

    String title = getIntent().getStringExtra(EXTRA_TITLE);
    if (title != null) {
        setSharedTitle(intent, title);
    }

    String text = getIntent().getStringExtra(Intent.EXTRA_TEXT);
    if (text==null) {
      CharSequence cs = getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT);
      if (cs!=null) {
        text = cs.toString();
      }
    }

    if (text != null) {
      setSharedText(intent, text.toString());
    }

    String subject = getIntent().getStringExtra(EXTRA_MSG_SUBJECT);
    if (subject != null) {
      setSharedSubject(intent, subject);
    }

    Uri html = getIntent().getParcelableExtra(EXTRA_MSG_HTML);
    if (html != null) {
      setSharedHtml(intent, html);
    }

    if (resolvedExtras.size() > 0) {
      Uri data = resolvedExtras.get(0);
      if (data != null) {
        String mimeType = getMimeType(data);
        intent.setDataAndType(data, mimeType);
      }
    }
    return intent;
  }

  private String getMimeType(@Nullable Uri uri) {
    if (uri != null) {
      final String mimeType = MediaUtil.getMimeType(getApplicationContext(), uri);
      if (mimeType != null) return mimeType;
    }
    return MediaUtil.getCorrectedMimeType(getIntent().getType());
  }

  private boolean hasFileScheme(Uri uri) {
    if (uri==null) {
      return false;
    }
    return "file".equals(uri.getScheme());
  }

}
