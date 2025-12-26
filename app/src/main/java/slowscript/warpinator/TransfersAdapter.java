package slowscript.warpinator;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.SpannableString;
import android.text.format.Formatter;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.base.Joiner;

public class TransfersAdapter extends RecyclerView.Adapter<TransfersAdapter.ViewHolder> {

    TransfersActivity activity;
    private static final String TAG = "TransferAdapter";

    public TransfersAdapter(TransfersActivity _app) {
        activity = _app;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(activity);
        View view = inflater.inflate(R.layout.transfer_view, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Transfer t = activity.remote.transfers.get(position);

        //ProgressBar
        holder.progressBar.setVisibility(t.getStatus() == Transfer.Status.TRANSFERRING ? View.VISIBLE : View.INVISIBLE);
        holder.progressBar.setProgress(t.getProgress());
        //Buttons
        if (t.getStatus() == Transfer.Status.WAITING_PERMISSION) {
            if (t.direction == Transfer.Direction.RECEIVE) {
                holder.btnAccept.setOnClickListener((v) -> t.startReceive());
                holder.btnAccept.setVisibility(View.VISIBLE);
            } else holder.btnAccept.setVisibility(View.INVISIBLE);
            holder.btnDecline.setOnClickListener((v) -> t.declineTransfer());
            holder.btnDecline.setVisibility(View.VISIBLE);
        } else {
            holder.btnAccept.setVisibility(View.INVISIBLE);
            holder.btnDecline.setVisibility(View.INVISIBLE);
        }
        holder.btnStop.setVisibility(t.getStatus() == Transfer.Status.TRANSFERRING ? View.VISIBLE : View.INVISIBLE);
        holder.btnStop.setOnClickListener((v) -> t.stop(false));
        if (t.direction == Transfer.Direction.SEND && (t.getStatus() == Transfer.Status.FAILED ||
                t.getStatus() == Transfer.Status.STOPPED || t.getStatus() == Transfer.Status.FINISHED_WITH_ERRORS ||
                t.getStatus() == Transfer.Status.DECLINED)) {
            holder.btnRetry.setVisibility(View.VISIBLE);
            holder.btnRetry.setOnClickListener((v) -> {
                t.setStatus(t.message != null ? Transfer.Status.TRANSFERRING : Transfer.Status.WAITING_PERMISSION);
                t.updateUI();
                if (t.message == null)
                    activity.remote.startSendTransfer(t);
                else
                    activity.remote.sendTextMessage(t);
            });
        } else holder.btnRetry.setVisibility(View.INVISIBLE);
        if ((t.getStatus() == Transfer.Status.FINISHED && t.direction == Transfer.Direction.RECEIVE)) {
            holder.btnCopy.setVisibility(View.VISIBLE);
            holder.btnCopy.setOnClickListener((v) -> {
                ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Message", t.message);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(activity, R.string.message_copied, Toast.LENGTH_SHORT).show();
            });
        } else holder.btnCopy.setVisibility(View.INVISIBLE);
        //Main label
        String text = t.message != null ? t.message : (t.fileCount == 1 ? t.singleName: activity.getString(R.string.num_files, t.fileCount));
        if (t.message == null)
            text += " (" + Formatter.formatFileSize(activity, t.totalSize) + ")";
        holder.txtTransfer.setText(text);
        //Status label
        switch (t.getStatus()) {
            case WAITING_PERMISSION:
                String str = activity.getString(R.string.waiting_for_permission);
                if (t.overwriteWarning)
                    str += " " + activity.getString(R.string.files_overwritten_warning);
                holder.txtStatus.setText(str);
                break;
            case TRANSFERRING:
                String status = Formatter.formatFileSize(activity, t.bytesTransferred) + "/" +
                    Formatter.formatFileSize(activity, t.totalSize) + " (" +
                    Formatter.formatFileSize(activity, t.bytesPerSecond) + "/s, " + getRemainingTime(t) + ")";
                holder.txtStatus.setText(status);
                break;
            case FINISHED:
                if (t.message != null) {
                    holder.txtStatus.setText(t.direction == Transfer.Direction.SEND ? R.string.sent : R.string.received);
                    break;
                }
            default:
                holder.txtStatus.setText(activity.getResources().getStringArray(R.array.transfer_states)[t.getStatus().ordinal()]);
        }
        //Images
        holder.imgFromTo.setImageResource(t.message != null ? R.drawable.ic_message : (
                t.direction == Transfer.Direction.SEND ? R.drawable.ic_upload : R.drawable.ic_download));
        holder.root.setOnClickListener((v)-> {
            if (t.getStatus() == Transfer.Status.FAILED || t.getStatus() == Transfer.Status.FINISHED_WITH_ERRORS) {
                Context c = holder.root.getContext();
                Utils.displayMessage(c, c.getString(R.string.errors_during_transfer), Joiner.on("\n").join(t.errors));
            } else if (t.getStatus() == Transfer.Status.TRANSFERRING) {
                String remaining = getRemainingTime(t) + " " + activity.getString(R.string.remaining);
                Toast.makeText(activity, remaining, Toast.LENGTH_SHORT).show();
            } else if (t.getStatus() == Transfer.Status.WAITING_PERMISSION && t.fileCount > 1) {
                Utils.displayMessage(activity, activity.getString(R.string.files_being_sent), Joiner.on("\n").join(t.topDirBasenames));
            } else if (t.getStatus() == Transfer.Status.FINISHED && t.direction == Transfer.Direction.RECEIVE) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                if (t.message != null) {
                    // Dialog with clickable links (see https://stackoverflow.com/a/3367392)
                    final SpannableString s = new SpannableString(t.message);
                    Linkify.addLinks(s, Linkify.WEB_URLS | Linkify.EMAIL_ADDRESSES | Linkify.PHONE_NUMBERS);
                    var dialog = new MaterialAlertDialogBuilder(activity)
                            .setTitle("Message")
                            .setMessage(s)
                            .setPositiveButton(android.R.string.ok, null)
                            .create();
                    dialog.show();
                    // Must be called after show()
                    ((TextView)dialog.findViewById(android.R.id.message)).setMovementMethod(LinkMovementMethod.getInstance());
                    return;
                } else if (t.fileCount == 1) {
                    Uri uri;
                    if (t.currentFile != null) {
                        uri = FileProvider.getUriForFile(activity, activity.getApplicationContext().getPackageName() + ".provider", t.currentFile);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } else uri = t.currentUri;
                    intent.setDataAndType(uri, t.singleMime);
                } else {
                    Uri u = Uri.parse(Server.current.downloadDirUri);
                    if (Server.current.downloadDirUri.startsWith("content:/")) {
                        String id = DocumentsContract.getTreeDocumentId(u);
                        u = DocumentsContract.buildDocumentUriUsingTree(u, id);
                    }
                    intent.setDataAndType(u, DocumentsContract.Document.MIME_TYPE_DIR);
                }
                try {
                    Intent chooser = Intent.createChooser(intent, null);
                    activity.startActivity(chooser);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to open received file", e);
                    Toast.makeText(activity, R.string.open_failed, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return activity.remote.transfers.size();
    }

    String getRemainingTime(Transfer t) {
        long now = System.currentTimeMillis();
        float avgSpeed = t.bytesTransferred / ((now - t.actualStartTime) / 1000f);
        int secondsRemaining = (int)((t.totalSize - t.bytesTransferred) / avgSpeed);
        return formatTime(secondsRemaining);
    }

    @SuppressLint("DefaultLocale")
    String formatTime(int seconds) {
        if (seconds > 60) {
            int minutes = seconds / 60;
            if (seconds > 3600) {
                int hours = seconds / 3600;
                minutes -= hours * 60;
                return String.format("%dh %dm", hours, minutes);
            }
            seconds -= minutes*60;
            return String.format("%dm %ds", minutes, seconds);
        }
        else if (seconds > 5) {
            return String.format("%ds", seconds);
        }
        else {
            return activity.getString(R.string.a_few_seconds);
        }
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        ImageButton btnAccept;
        ImageButton btnDecline;
        ImageButton btnStop;
        ImageButton btnRetry;
        ImageButton btnCopy;
        ImageView imgFromTo;
        TextView txtTransfer;
        TextView txtStatus;
        ProgressBar progressBar;
        View root;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            root = itemView;
            btnAccept = itemView.findViewById(R.id.btnAccept);
            btnDecline = itemView.findViewById(R.id.btnDecline);
            btnStop = itemView.findViewById(R.id.btnStop);
            btnRetry = itemView.findViewById(R.id.btnRetry);
            btnCopy = itemView.findViewById(R.id.btnCopyMessage);
            imgFromTo = itemView.findViewById(R.id.imgFromTo);
            txtStatus = itemView.findViewById(R.id.txtStatus);
            txtTransfer = itemView.findViewById(R.id.txtTransfer);
            progressBar = itemView.findViewById(R.id.progressBar);
        }
    }
}
