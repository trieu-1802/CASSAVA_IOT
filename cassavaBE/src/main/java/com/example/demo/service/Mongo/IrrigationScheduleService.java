package com.example.demo.service.Mongo;

import com.example.demo.entity.MongoEntity.Field;
import com.example.demo.entity.MongoEntity.IrrigationSchedule;
import com.example.demo.entity.MongoEntity.IrrigationSchedule.Status;
import com.example.demo.repositories.mongo.FieldMongoRepository;
import com.example.demo.repositories.mongo.IrrigationScheduleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class IrrigationScheduleService {

    @Autowired
    private IrrigationScheduleRepository scheduleRepository;

    @Autowired
    private FieldMongoRepository fieldMongoRepository;

    public IrrigationSchedule create(IrrigationSchedule schedule) {
        if (schedule.getFieldId() == null || schedule.getFieldId().trim().isEmpty()) {
            throw new IllegalArgumentException("fieldId không được để trống");
        }
        Field field = fieldMongoRepository.findById(schedule.getFieldId())
                .orElseThrow(() -> new RuntimeException("Không tìm thấy cánh đồng ID: " + schedule.getFieldId()));

        if (field.isAutoIrrigation()) {
            throw new RuntimeException("Cánh đồng đang ở chế độ tưới tự động, không thể đặt lịch tưới tay");
        }

        Integer valveId = schedule.getValveId() != null ? schedule.getValveId() : field.getValveId();
        if (valveId == null || valveId < 1 || valveId > 4) {
            throw new RuntimeException("Cánh đồng chưa được gán van bơm hợp lệ (valveId 1-4)");
        }
        schedule.setValveId(valveId);

        if (schedule.getScheduledTime() == null) {
            throw new IllegalArgumentException("scheduledTime không được để trống");
        }
        if (schedule.getDurationSeconds() == null || schedule.getDurationSeconds() <= 0) {
            throw new IllegalArgumentException("durationSeconds phải > 0");
        }

        Date now = new Date();
        schedule.setId(null);
        schedule.setStatus(Status.PENDING);
        schedule.setCreatedAt(now);
        schedule.setUpdatedAt(now);
        schedule.setStartedAt(null);
        schedule.setFinishedAt(null);
        schedule.setErrorMessage(null);

        return scheduleRepository.save(schedule);
    }

    public List<IrrigationSchedule> getByFieldId(String fieldId) {
        return scheduleRepository.findByFieldIdOrderByScheduledTimeAsc(fieldId);
    }

    public List<IrrigationSchedule> getPendingByFieldId(String fieldId) {
        return scheduleRepository.findByFieldIdAndStatusOrderByScheduledTimeAsc(fieldId, Status.PENDING);
    }

    public List<IrrigationSchedule> getDuePending(Date before) {
        return scheduleRepository.findByStatusAndScheduledTimeBeforeOrderByScheduledTimeAsc(Status.PENDING, before);
    }

    public IrrigationSchedule cancel(String id) {
        IrrigationSchedule s = scheduleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy lịch tưới ID: " + id));
        if (s.getStatus() != Status.PENDING) {
            throw new RuntimeException("Chỉ có thể hủy lịch ở trạng thái PENDING (hiện tại: " + s.getStatus() + ")");
        }
        s.setStatus(Status.CANCELLED);
        s.setUpdatedAt(new Date());
        return scheduleRepository.save(s);
    }

    public IrrigationSchedule updateStatus(String id, Status status, String errorMessage) {
        IrrigationSchedule s = scheduleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy lịch tưới ID: " + id));
        Date now = new Date();
        s.setStatus(status);
        s.setUpdatedAt(now);
        if (status == Status.RUNNING && s.getStartedAt() == null) {
            s.setStartedAt(now);
        }
        if (status == Status.DONE || status == Status.FAILED || status == Status.CANCELLED) {
            s.setFinishedAt(now);
        }
        if (errorMessage != null) {
            s.setErrorMessage(errorMessage);
        }
        return scheduleRepository.save(s);
    }

    public void delete(String id) {
        scheduleRepository.deleteById(id);
    }

    public IrrigationSchedule markSent(String id) {
        IrrigationSchedule s = scheduleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy lịch tưới ID: " + id));
        Date now = new Date();
        s.setStatus(Status.SENT);
        s.setSentAt(now);
        s.setUpdatedAt(now);
        return scheduleRepository.save(s);
    }

    public IrrigationSchedule handleAck(String id, boolean ok, String errorMessage) {
        IrrigationSchedule s = scheduleRepository.findById(id).orElse(null);
        if (s == null) return null;
        Date now = new Date();
        s.setStatus(ok ? Status.DONE : Status.FAILED);
        s.setFinishedAt(now);
        s.setUpdatedAt(now);
        if (errorMessage != null) {
            s.setErrorMessage(errorMessage);
        }
        return scheduleRepository.save(s);
    }

    public List<IrrigationSchedule> getStaleSent(Date sentBefore) {
        return scheduleRepository.findByStatusAndSentAtBeforeOrderBySentAtAsc(Status.SENT, sentBefore);
    }

    public List<IrrigationSchedule> getRunning() {
        return scheduleRepository.findByStatus(Status.RUNNING);
    }
}
