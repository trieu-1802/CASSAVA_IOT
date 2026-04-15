// src/pages/Weather/WeatherFieldList.jsx
import React, { useEffect, useState } from 'react';
import { Card, List, Typography, Button, Spin, Empty, Tag, Space, message } from 'antd';
import { CloudOutlined, LoadingOutlined, RightOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import fieldService from '../../services/fieldService';

const { Title, Text } = Typography;

const WeatherFieldList = () => {
  const navigate = useNavigate();
  const [fields, setFields] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchFields = async () => {
      setLoading(true);
      try {
        const res = await fieldService.get('/field');
        setFields(res.data || []);
      } catch (err) {
        console.error('Lỗi tải danh sách cánh đồng:', err);
        message.error('Không thể tải danh sách cánh đồng.');
      } finally {
        setLoading(false);
      }
    };
    fetchFields();
  }, []);

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: '100px' }}>
        <Spin indicator={<LoadingOutlined style={{ fontSize: 48 }} spin />} />
        <div style={{ marginTop: 16 }}>Đang tải danh sách cánh đồng...</div>
      </div>
    );
  }

  return (
    <div style={{ padding: '24px' }}>
      <Card
        title={<Title level={3} style={{ margin: 0 }}>Trạm quan trắc - Chọn cánh đồng</Title>}
      >
        {fields.length === 0 ? (
          <Empty description="Chưa có cánh đồng nào." />
        ) : (
          <List
            itemLayout="horizontal"
            dataSource={fields}
            renderItem={(item) => (
              <List.Item
                style={{ padding: '16px 0' }}
                actions={[
                  <Button
                    type="primary"
                    ghost
                    onClick={() => navigate(`/weather/${item.id}`)}
                  >
                    Xem trạm quan trắc <RightOutlined />
                  </Button>,
                ]}
              >
                <List.Item.Meta
                  avatar={
                    <div
                      style={{
                        fontSize: 24,
                        background: '#f5f5f5',
                        padding: 12,
                        borderRadius: 8,
                        display: 'flex',
                        alignItems: 'center',
                      }}
                    >
                      <CloudOutlined style={{ color: '#1890ff' }} />
                    </div>
                  }
                  title={
                    <Space>
                      <Text strong style={{ fontSize: 16 }}>{item.name}</Text>
                      {item.autoIrrigation && <Tag color="green">Tưới tự động</Tag>}
                    </Space>
                  }
                  description={
                    <Text type="secondary">
                      Diện tích: {item.acreage} ha {item.DAP ? `• DAP: ${item.DAP}` : ''}
                    </Text>
                  }
                />
              </List.Item>
            )}
          />
        )}
      </Card>
    </div>
  );
};

export default WeatherFieldList;
