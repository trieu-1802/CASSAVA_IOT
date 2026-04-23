import React, { useEffect, useState } from 'react';
import { Typography, Spin, Button, Select, Space, Tag, message, Tooltip } from 'antd';
import { ReloadOutlined, PlayCircleOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import api from '../../../services/api';
import fieldService from '../../../services/fieldService';
import YieldTab from '../FieldDetail/YieldTab';

const { Title, Text } = Typography;

const MONTH_MAP = { Jan: 0, Feb: 1, Mar: 2, Apr: 3, May: 4, Jun: 5, Jul: 6, Aug: 7, Sep: 8, Oct: 9, Nov: 10, Dec: 11 };

// Parse ngày dạng "Thu Mar 16 00:00:00 ICT 2023" (java.util.Date.toString)
const parseJavaDateString = (s) => {
  const parts = s.split(' ');
  if (parts.length < 6) return null;
  const monthIndex = MONTH_MAP[parts[1]];
  const day = parseInt(parts[2], 10);
  const year = parseInt(parts[5], 10);
  if (monthIndex === undefined || isNaN(day) || isNaN(year)) return null;
  const d = new Date(year, monthIndex, day);
  return isNaN(d.getTime()) ? null : d;
};

const formatSeasonLabel = (s) => {
  const start = s.cropStartTime ? dayjs(s.cropStartTime).format('DD/MM/YYYY') : '—';
  if (s.isCurrent) {
    const end = s.cropEndTime ? dayjs(s.cropEndTime).format('DD/MM/YYYY') : 'hiện tại';
    return `${start} → ${end}`;
  }
  const end = s.cropEndTime ? dayjs(s.cropEndTime).format('DD/MM/YYYY') : '—';
  return `${start} → ${end}`;
};

const SimulationDashboard = ({ fieldId, fieldName }) => {
  const [chartData, setChartData] = useState([]);
  const [loading, setLoading] = useState(false);
  const [simulating, setSimulating] = useState(false);
  const [seasons, setSeasons] = useState([]);
  const [selectedCrop, setSelectedCrop] = useState(null);

  const loadSeasons = async ({ preserveSelection = false } = {}) => {
    try {
      const res = await fieldService.get(`/field/${fieldId}/seasons`);
      const list = res.data || [];
      setSeasons(list);
      if (!preserveSelection) {
        const current = list.find((s) => s.isCurrent) || list[0];
        setSelectedCrop(current?.cropStartTime || null);
      }
      return list;
    } catch (err) {
      console.error('Lỗi tải danh sách vụ:', err);
      message.error('Không thể tải danh sách vụ mùa!');
      return [];
    }
  };

  useEffect(() => {
    if (!fieldId) return;
    let cancelled = false;
    (async () => {
      const list = await loadSeasons();
      if (cancelled) return;
      // loadSeasons đã setSelectedCrop nên không cần làm gì thêm ở đây
      void list;
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fieldId]);

  const isCurrentSelected = seasons.some((s) => s.isCurrent && s.cropStartTime === selectedCrop);

  const handleSimulate = async () => {
    if (!fieldId) return;
    setSimulating(true);
    try {
      const res = await api.get('/simulation/run', { params: { fieldId } });
      message.success(res.data?.message || 'Mô phỏng hoàn tất!');
      // Refresh seasons + chart; giữ vị trí chọn hiện tại (là vụ đang chạy)
      await loadSeasons({ preserveSelection: true });
      await fetchChartData(selectedCrop);
    } catch (err) {
      console.error('Simulate error:', err);
      const serverMsg = (typeof err.response?.data === 'string' && err.response.data) || err.message;
      message.error(`Không thể chạy mô phỏng: ${serverMsg || 'lỗi không xác định'}`);
    } finally {
      setSimulating(false);
    }
  };

  const fetchChartData = async (cropStartTime = selectedCrop) => {
    if (!fieldId) return;
    setLoading(true);
    try {
      const params = { fieldId };
      if (cropStartTime) params.cropStartTime = cropStartTime;
      const response = await api.get('/simulation/chart', { params });

      const { day, yield: yields, irrigation, leafArea, labels } = response.data;
      const formattedData = (day || []).map((d, index) => {
        const dateObj = parseJavaDateString(d);
        return {
          time: dateObj
            ? dateObj.toLocaleDateString('vi-VN', { day: '2-digit', month: '2-digit' })
            : 'Lỗi',
          fullTime: d,
          yield: yields?.[index] || 0,
          irrigation: irrigation?.[index] || 0,
          leafArea: leafArea?.[index] || 0,
          labileCarbon: labels?.[index] || 0,
        };
      });
      setChartData(formattedData);
    } catch (error) {
      console.error('Error details:', error);
      message.error('Không thể tải dữ liệu mô phỏng');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (fieldId && selectedCrop !== null) fetchChartData(selectedCrop);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fieldId, selectedCrop]);

  return (
    <div style={{ padding: '24px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 24, flexWrap: 'wrap', gap: 12 }}>
        <Title level={3} style={{ margin: 0 }}>Kết quả mô phỏng: {fieldName || fieldId}</Title>
        <Space size={8} wrap>
          <Text strong>Mùa vụ:</Text>
          <Select
            style={{ minWidth: 260 }}
            value={selectedCrop}
            onChange={setSelectedCrop}
            options={seasons.map((s) => ({
              value: s.cropStartTime,
              label: (
                <Space size={6}>
                  <span>{formatSeasonLabel(s)}</span>
                  {s.isCurrent && <Tag color="green" style={{ margin: 0 }}>Đang chạy</Tag>}
                </Space>
              ),
            }))}
            placeholder="Chọn mùa vụ"
            notFoundContent="Chưa có vụ nào"
          />
          <Tooltip
            title={isCurrentSelected ? '' : 'Chỉ có thể chạy mô phỏng cho vụ đang chạy'}
          >
            <Button
              type="primary"
              icon={<PlayCircleOutlined />}
              onClick={handleSimulate}
              loading={simulating}
              disabled={!isCurrentSelected || simulating}
            >
              Chạy mô phỏng
            </Button>
          </Tooltip>
          <Button icon={<ReloadOutlined />} onClick={() => fetchChartData(selectedCrop)} loading={loading}>
            Làm mới
          </Button>
        </Space>
      </div>

      {loading ? (
        <div style={{ textAlign: 'center', padding: '100px' }}>
          <Spin size="large" tip="Đang tải dữ liệu biểu đồ..." />
        </div>
      ) : (
        <YieldTab data={chartData} />
      )}
    </div>
  );
};

export default SimulationDashboard;
