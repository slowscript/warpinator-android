package slowscript.warpinator;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

public class RemotesAdapter extends RecyclerView.Adapter<RemotesAdapter.ViewHolder> {

    Activity app;

    public RemotesAdapter(Activity _app) {
        app = _app;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(app);
        View view = inflater.inflate(R.layout.remote_view, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Remote r = (Remote) MainService.remotes.values().toArray()[position];
        setupViewHolder(holder, r);

        holder.cardView.setOnClickListener((view) -> {
            Intent i = new Intent(app, TransfersActivity.class);
            i.putExtra("remote", r.uuid);
            app.startActivity(i);
        });
        holder.cardView.setOnLongClickListener((view) -> {
            //app.getMenuInflater().inflate();
            return true;
        });
    }

    void setupViewHolder(ViewHolder holder, Remote r) {
        holder.txtName.setText(r.displayName);
        holder.txtUsername.setText(r.userName + "@" + r.hostname);
        holder.txtIP.setText(r.address.getHostAddress() + ":" + r.port);
        if(r.picture != null) {
            holder.imgProfile.setImageBitmap(r.picture);
            holder.imgProfile.setImageTintList(null);
        } else { //Keep default, apply tint
            holder.imgProfile.setImageTintList(ColorStateList.valueOf(app.getResources().getColor(R.color.whiteOnPurple)));
        }
        holder.imgStatus.setImageResource(Utils.getIconForRemoteStatus(r.status));
        if (r.status == Remote.RemoteStatus.ERROR || r.status == Remote.RemoteStatus.DISCONNECTED) {
            holder.imgStatus.setImageTintList(null);
        } else {
            holder.imgStatus.setImageTintList(ColorStateList.valueOf(app.getResources().getColor(R.color.whiteOnPurple)));
        }
    }

    @Override
    public int getItemCount() {
        return MainService.remotes.size();
    }

    public class ViewHolder extends RecyclerView.ViewHolder{

        CardView cardView;
        TextView txtName;
        TextView txtUsername;
        TextView txtIP;
        ImageView imgProfile;
        ImageView imgStatus;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.cardView);
            txtName = itemView.findViewById(R.id.txtName);
            txtUsername = itemView.findViewById(R.id.txtUsername);
            txtIP = itemView.findViewById(R.id.txtIP);
            imgProfile = itemView.findViewById(R.id.imgProfile);
            imgStatus = itemView.findViewById(R.id.imgStatus);
        }
    }
}
