import React, { useEffect, useState } from 'react';
import { Card, List, Typography, Button, Tag, Space, Spin, message } from 'antd';
import {
  CloudOutlined,
  FireOutlined,
  ThunderboltOutlined,
  CompassOutlined,
  DashboardOutlined,
  LineChartOutlined,
  RightOutlined,
  LoadingOutlined,
  ApartmentOutlined,
  ArrowLeftOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import groupService from '../../services/groupService';

const { Title, Text } = Typography;

const SENSOR_CONFIG = {
  temperature: { name: 'Nhiệt độ môi trường', unit: '°C', icon: <FireOutlined style={{ color: '#cf1322' }} /> },
  relativeHumidity: { name: 'Độ ẩm không khí', unit: '%', icon: <CloudOutlined style={{ color: '#096dd9' }} /> },
  rain: { name: 'Lượng mưa tích lũy', unit: 'mm', icon: <DashboardOutlined style={{ color: '#3f6600' }} /> },
  radiation: { name: 'Bức xạ mặt trời', unit: 'W/m²', icon: <ThunderboltOutlined style={{ color: '#d48806' }} /> },
  wind: { name: 'Tốc độ gió', unit: 'm/s', icon: <CompassOutlined style={{ color: '#531dab' }} /> },
};

const getSensorConfig = (sensorId) =>
  SENSOR_CONFIG[sensorId] || { name: sensorId, unit: '', icon: <DashboardOutlined /> };

const WeatherDashboard = () => {
  const navigate = useNavigate();
  const { groupId } = useParams();

  const [groupInfo, setGroupInfo] = useState(null);
  const [groupSensors, setGroupSensors] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!groupId) return;
    let cancelled = false;

    const load = async () => {
      setLoading(true);
      try {
        const [gRes, gsRes] = await Promise.all([
          groupService.get(`/${groupId}`),
          groupService.get(`/${groupId}/sensor`),
        ]);
        if (cancelled) return;
        setGroupInfo(gRes.data);
        setGroupSensors(gsRes.data || []);
      } catch (err) {
        console.error('Lỗi tải dữ liệu trạm thời tiết:', err);
        message.error('Không thể tải dữ liệu trạm thời tiết!');
      } finally {
        if (!cancelled) setLoading(false);
      }
    };

    load();
    return () => { cancelled = true; };
  }, [groupId]);

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: '100px' }}>
        <Spin indicator={<LoadingOutlined style={{ fontSize: 48 }} spin />} />
        <div style={{ marginTop: 16 }}>Đang tải danh sách cảm biến...</div>
      </div>
    );
  }

  return (
    <div style={{ padding: '24px' }}>
      <Button
        icon={<ArrowLeftOutlined />}
        onClick={() => navigate('/weather')}
        style={{ marginBottom: 16 }}
      >
        Quay lại danh sách nhóm
      </Button>

      <Card
        title={
          <Space wrap>
            <ApartmentOutlined />
            <Title level={3} style={{ margin: 0 }}>
              Trạm thời tiết: {groupInfo?.name || groupId}
            </Title>
          </Space>
        }
        extra={<Tag color="green">Đang kết nối</Tag>}
      >
        <Text type="secondary">
          5 cảm biến thời tiết dùng chung cho mọi cánh đồng trong nhóm.
        </Text>

        <List
          style={{ marginTop: 16 }}
          itemLayout="horizontal"
          dataSource={groupSensors}
          locale={{ emptyText: 'Nhóm chưa có cảm biến thời tiết.' }}
          renderItem={(item) => {
            const config = getSensorConfig(item.sensorId);
            return (
              <List.Item
                style={{ padding: '20px 0' }}
                actions={[
                  <Button
                    type="primary"
                    ghost
                    icon={<LineChartOutlined />}
                    onClick={() =>
                      navigate(`/weather/detail/${item.sensorId}?groupId=${groupId}`)
                    }
                  >
                    Xem đồ thị <RightOutlined />
                  </Button>,
                ]}
              >
                <List.Item.Meta
                  avatar={
                    <div style={{
                      fontSize: '24px',
                      background: '#f5f5f5',
                      padding: '12px',
                      borderRadius: '8px',
                      display: 'flex',
                      alignItems: 'center',
                    }}>
                      {config.icon}
                    </div>
                  }
                  title={<Text strong style={{ fontSize: '16px' }}>{config.name}</Text>}
                  description={
                    <Space direction="vertical" size={2}>
                      <Text type="secondary">ID cảm biến: {item.sensorId}</Text>
                      <Text type="secondary">Đơn vị: {config.unit || '—'}</Text>
                    </Space>
                  }
                />
              </List.Item>
            );
          }}
        />
      </Card>
    </div>
  );
};

export default WeatherDashboard;
