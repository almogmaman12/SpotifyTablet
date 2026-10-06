package com.almog.spotifytablet;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class CalendarEventAdapter extends RecyclerView.Adapter<CalendarEventAdapter.EventViewHolder> {
    private List<CalendarEvent> events;

    public CalendarEventAdapter(List<CalendarEvent> events) {
        this.events = events;
    }

    public void updateEvents(List<CalendarEvent> newEvents) {
        this.events = newEvents;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public EventViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_calendar_event, parent, false);
        return new EventViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull EventViewHolder holder, int position) {
        CalendarEvent event = events.get(position);
        holder.eventName.setText(event.name);
        holder.eventCountdown.setText(event.getCountdownString());

        long fiveDaysMs = 5L * 24 * 60 * 60 * 1000;
        if (event.timeUntilMs < fiveDaysMs && event.timeUntilMs > 0) {
            holder.eventName.setTextColor(android.graphics.Color.parseColor("#FF4444"));
            holder.eventName.setTypeface(null, android.graphics.Typeface.BOLD);
            holder.eventCountdown.setTextColor(android.graphics.Color.parseColor("#FF4444"));
            holder.eventCountdown.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            holder.eventName.setTextColor(android.graphics.Color.parseColor("#FFFFFF"));
            holder.eventName.setTypeface(null, android.graphics.Typeface.NORMAL);
            holder.eventCountdown.setTextColor(android.graphics.Color.parseColor("#AAAAAA"));
            holder.eventCountdown.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
        
        // Pulse red if < 24h
        if (event.timeUntilMs > 0 && event.timeUntilMs < 24 * 60 * 60 * 1000L) {
            holder.eventCountdown.animate()
                    .alpha(0.5f)
                    .setDuration(800)
                    .withEndAction(() -> holder.eventCountdown.animate().alpha(1f).setDuration(800).start())
                    .start();
        } else {
            holder.eventCountdown.clearAnimation();
            holder.eventCountdown.setAlpha(1f);
        }
    }

    @Override
    public int getItemCount() {
        return events == null ? 0 : events.size();
    }

    static class EventViewHolder extends RecyclerView.ViewHolder {
        TextView eventName;
        TextView eventCountdown;

        EventViewHolder(View itemView) {
            super(itemView);
            eventName = itemView.findViewById(R.id.itemEventName);
            eventCountdown = itemView.findViewById(R.id.itemEventCountdown);
        }
    }

    public static class CalendarEvent {
        public String name;
        public long timeUntilMs;
        public long eventTimeMs;

        public CalendarEvent(String name, long timeUntilMs, long eventTimeMs) {
            this.name = name;
            this.timeUntilMs = timeUntilMs;
            this.eventTimeMs = eventTimeMs;
        }

        public String getCountdownString() {
            String dateStr = new java.text.SimpleDateFormat("dd/MM", java.util.Locale.US).format(new java.util.Date(eventTimeMs));
            
            if (timeUntilMs <= 0) return dateStr + " NOW";
            long seconds = timeUntilMs / 1000;
            long mins = (seconds / 60) % 60;
            long hours = (seconds / 3600) % 24;
            long days = seconds / 86400;
            
            if (days > 0) {
                return String.format(java.util.Locale.US, "%s   %dd %02dh", dateStr, days, hours);
            } else {
                return String.format(java.util.Locale.US, "%s   %02dh", dateStr, hours);
            }
        }
    }
}
