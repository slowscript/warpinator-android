package slowscript.warpinator;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.recyclerview.widget.RecyclerView;

import com.google.common.base.Joiner;

public class TransfersAdapter extends RecyclerView.Adapter<TransfersAdapter.ViewHolder> {

    TransfersActivity activity;

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
        //Main label
        String text = t.fileCount == 1 ? t.singleName: t.fileCount + " files";
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
                long now = System.currentTimeMillis();
                float avgSpeed = t.bytesTransferred / ((now - t.actualStartTime) / 1000f);
                int secondsRemaining = (int)((t.totalSize - t.bytesTransferred) / avgSpeed);
                String status = Formatter.formatFileSize(activity, t.bytesTransferred) + " / " +
                    Formatter.formatFileSize(activity, t.totalSize) + "(" +
                    Formatter.formatFileSize(activity, t.bytesPerSecond) + "/s, " + formatTime(secondsRemaining);
                holder.txtStatus.setText(status);
                break;
            default:
                holder.txtStatus.setText(activity.getResources().getStringArray(R.array.transfer_states)[t.getStatus().ordinal()]);
        }
        //Images
        holder.imgFromTo.setImageResource(t.direction == Transfer.Direction.SEND ? R.drawable.ic_upload : R.drawable.ic_download);
        holder.root.setOnClickListener((v)-> {
            if (t.getStatus() == Transfer.Status.FAILED || t.getStatus() == Transfer.Status.FINISHED_WITH_ERRORS) {
                new AlertDialog.Builder(holder.root.getContext())
                        .setTitle("Errors during transfer:")
                        .setMessage(Joiner.on("\n").join(t.errors))
                        .show();
            } else if (t.getStatus() == Transfer.Status.TRANSFERRING) {
                long now = System.currentTimeMillis();
                float avgSpeed = t.bytesTransferred / ((now - t.actualStartTime) / 1000f);
                int secondsRemaining = (int)((t.totalSize - t.bytesTransferred) / avgSpeed);
                String remainingTime = formatTime(secondsRemaining) + " " + activity.getString(R.string.remaining);
                Toast.makeText(activity, remainingTime, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public int getItemCount() {
        return activity.remote.transfers.size();
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
            return String.format("%d s", seconds);
        }
        else {
            return "a few seconds";
        }
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        AppCompatImageButton btnAccept;
        AppCompatImageButton btnDecline;
        AppCompatImageButton btnStop;
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
            imgFromTo = itemView.findViewById(R.id.imgFromTo);
            txtStatus = itemView.findViewById(R.id.txtStatus);
            txtTransfer = itemView.findViewById(R.id.txtTransfer);
            progressBar = itemView.findViewById(R.id.progressBar);
        }
    }
}
