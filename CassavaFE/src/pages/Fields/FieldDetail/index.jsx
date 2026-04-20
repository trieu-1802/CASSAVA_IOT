// src/pages/Fields/FieldDetail/index.jsx
import React, { useState, useEffect } from 'react';
import { Tabs, Card, Typography, Breadcrumb, Button } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { useParams, useNavigate } from 'react-router-dom';

import IrrigationTab from './IrrigationTab';
import YieldTab from './YieldTab';
import HistoryTab from './HistoryTab';
import DiseaseTab from './DiseaseTab';
import SimulationDashboard from '../components/SimulationDashboard';
import fieldService from '../../../services/fieldService';
import ManualIrrigationTab from './ManualIrrigationTab';
const { Title } = Typography;

const FieldDetailIndex = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const [fieldName, setFieldName] = useState('');
  const [isAuto, setIsAuto] = useState(false);

  useEffect(() => {
    fieldService.get(`/field/${id}`)
      .then(res => {
        setFieldName(res.data?.name || id);
        setIsAuto(res.data?.autoIrrigation);
      })
      .catch(() => setFieldName(id));
  }, [id]);

  const tabItems = [
    { key: '1', label: 'Theo dõi tưới tiêu', children: <IrrigationTab fieldId={id} /> },
   // { key: '2', label: 'Dự đoán sản lượng', children: <SimulationDashboard fieldId={id} /> },
   ...(isAuto === true ? [{
      key: '2',
      label: 'Dự đoán sản lượng',
      children: <SimulationDashboard fieldId={id} fieldName={fieldName} />
    }] : []),
    // Nếu isAuto là false -> Hiện Cài đặt tưới tay
    ...(isAuto === false ? [{
      key: '2-manual',
      label: 'Cài đặt tưới tay',
      children: <ManualIrrigationTab fieldId={id} fieldName={fieldName} />
    }] : []),
    { key: '3', label: 'Lịch sử tưới', children: <HistoryTab fieldId={id} />},
    { key: '4', label: 'Tình trạng bệnh', children: <DiseaseTab /> },
  ];

  return (
    <div>
      <Breadcrumb style={{ marginBottom: '16px' }}>
        <Breadcrumb.Item>
          <a onClick={() => navigate(-1)}>Danh sách cánh đồng</a>
        </Breadcrumb.Item>
        <Breadcrumb.Item>{fieldName}</Breadcrumb.Item>
      </Breadcrumb>

      <Card>
        <div style={{ display: 'flex', alignItems: 'center', marginBottom: '20px' }}>
          <Button
            type="text"
            icon={<ArrowLeftOutlined />}
            onClick={() => navigate(-1)}
            style={{ marginRight: '16px' }}
          />
          <Title level={3} style={{ margin: 0 }}>{fieldName}</Title>
        </div>

        <Tabs defaultActiveKey="1" items={tabItems} />
      </Card>
    </div>
  );
};

export default FieldDetailIndex;
